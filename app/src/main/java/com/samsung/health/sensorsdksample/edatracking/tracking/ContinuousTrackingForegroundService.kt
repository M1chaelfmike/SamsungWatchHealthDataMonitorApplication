package com.samsung.health.sensorsdksample.edatracking.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
import com.samsung.health.sensorsdksample.edatracking.pairing.WatchPairingManager
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity.Companion.APP_TAG
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ContinuousTrackingForegroundService : Service() {

    @Inject
    lateinit var trackingManager: ContinuousTrackingManager

    @Inject
    lateinit var watchPairingManager: WatchPairingManager

    // =====================================================
    // BLE Advertising (watch as iBeacon/tag)
    // =====================================================
    private val beaconUuid: UUID = UUID.fromString("8f0a5a8c-6c3a-4c4f-9e2b-2c9c9f3c9e10")
    private val beaconMajor: Int = 1
    private val beaconMinor: Int = 1
    private val beaconMeasuredPower: Byte = (-59).toByte()

    private var beaconAdvertiseCallback: AdvertiseCallback? = null
    private var beaconAdvertisingSetCallback: AdvertisingSetCallback? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingCoordinatorJob: Job? = null
    private var notificationStateJob: Job? = null
    private var recoveryJob: Job? = null
    private var messageLogJob: Job? = null
    private var chargingHeartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var wearSensor: Sensor? = null
    private var lastWearState: Boolean? = null
    private var lastChargingState: Boolean? = null
    private var lastChargeSource: String? = null
    private var lastBatteryLevelPercent: Int? = null
    private var serviceStopStateReported = false
    private var batteryReceiverRegistered = false
    private var screenReceiverRegistered = false
    private val wearListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val isWorn = event.values.firstOrNull() == 1.0f
            if (lastWearState == isWorn) {
                return
            }
            lastWearState = isWorn
            ensureBeaconAdvertisingRunning()
            trackingManager.onWearStateChanged(isWorn)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(APP_TAG, "Screen off detected in foreground service")
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(APP_TAG, "Screen on detected in foreground service")
                }
            }
        }
    }
    private val batteryStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleBatteryIntent(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTrackingService()
            else -> startTrackingService()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        shutdownTrackingInfrastructure(reportServiceStoppedUnworn = true)
        super.onDestroy()
    }

    private fun startTrackingService() {
        serviceStopStateReported = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(
            title = getString(R.string.continuous_service_title),
            text = getString(R.string.continuous_service_text)
        ))
        // Keep the iBeacon alive for indoor positioning even when the watch is off-wrist.
        ensureBeaconAdvertisingRunning()
        acquireWakeLock()
        registerScreenReceiver()
        registerBatteryReceiver()
        registerWearSensor()
        watchPairingManager.start()
        trackingManager.connect()
        if (chargingHeartbeatJob == null) {
            chargingHeartbeatJob = serviceScope.launch {
                while (isActive) {
                    delay(CHARGING_POWER_STATUS_INTERVAL_MILLIS)
                    if (lastChargingState == true) {
                        publishLatestPowerState()
                    }
                }
            }
        }
        if (trackingCoordinatorJob == null) {
            trackingCoordinatorJob = serviceScope.launch {
                combine(
                    trackingManager.connectionState,
                    watchPairingManager.pairingState
                ) { connectionState, pairingState ->
                    Pair(connectionState, pairingState)
                }.collectLatest { (connectionState, pairingState) ->
                    if (pairingState.requiresPairing) {
                        if (trackingManager.progressState.value == com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.Tracking) {
                            trackingManager.stopTracking()
                        }
                        return@collectLatest
                    }

                    if (connectionState == com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState.Connected &&
                        lastWearState != false &&
                        trackingManager.progressState.value != com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.Tracking &&
                        trackingManager.progressState.value != com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.TrackingDisabled
                    ) {
                        trackingManager.startTracking()
                    }
                }
            }
        }
        if (notificationStateJob == null) {
            notificationStateJob = serviceScope.launch {
                combine(
                    trackingManager.connectionState,
                    trackingManager.progressState,
                    trackingManager.dataState
                ) { connectionState, progressState, dataState ->
                    Triple(connectionState, progressState, dataState)
                }.collectLatest { (connectionState, progressState, dataState) ->
                    updateNotification(
                        title = buildNotificationTitle(progressState, dataState.wearStatusSnapshot?.isWorn),
                        text = buildNotificationText(connectionState, progressState, dataState)
                    )
                }
            }
        }
        if (recoveryJob == null) {
            recoveryJob = serviceScope.launch {
                while (isActive) {
                    delay(5_000L)
                    if (watchPairingManager.pairingState.value.requiresPairing) {
                        if (trackingManager.progressState.value == com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.Tracking) {
                            trackingManager.stopTracking()
                        }
                        continue
                    }

                    if (lastWearState == false) {
                        continue
                    }

                    when (trackingManager.connectionState.value) {
                        com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState.Disconnected -> {
                            trackingManager.reconnect()
                        }

                        com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState.Connected -> {
                            if (trackingManager.progressState.value == com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.Idle) {
                                trackingManager.startTracking()
                                continue
                            }
                        }
                    }
                }
            }
        }
        if (messageLogJob == null) {
            messageLogJob = serviceScope.launch {
                trackingManager.messageState.collectLatest { messageState ->
                    when (messageState) {
                        is ContinuousTrackingMessageState.Info -> Log.i(APP_TAG, "Service message: ${messageState.message}")
                        is ContinuousTrackingMessageState.Error -> Log.w(APP_TAG, "Service error: ${messageState.errorMessage}")
                        is ContinuousTrackingMessageState.PermissionError -> Log.w(APP_TAG, "Service permission error")
                        is ContinuousTrackingMessageState.TrackingInUse -> Log.w(APP_TAG, "Service tracking already in use")
                        is ContinuousTrackingMessageState.ResolvableError -> Log.w(APP_TAG, "Service resolvable HTS error")
                        is ContinuousTrackingMessageState.UnsupportedSensors -> Log.w(APP_TAG, "Service unsupported sensors")
                    }
                }
            }
        }
    }

    private fun stopTrackingService() {
        shutdownTrackingInfrastructure(reportServiceStoppedUnworn = true)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun shutdownTrackingInfrastructure(reportServiceStoppedUnworn: Boolean) {
        if (reportServiceStoppedUnworn && !serviceStopStateReported) {
            trackingManager.reportServiceStoppedAsNotWorn()
            serviceStopStateReported = true
        }
        trackingCoordinatorJob?.cancel()
        trackingCoordinatorJob = null
        notificationStateJob?.cancel()
        notificationStateJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        messageLogJob?.cancel()
        messageLogJob = null
        chargingHeartbeatJob?.cancel()
        chargingHeartbeatJob = null
        stopBeaconAdvertisingIfNeeded()
        unregisterScreenReceiver()
        unregisterBatteryReceiver()
        unregisterWearSensor()
        releaseWakeLock()
        trackingManager.stopTracking()
        trackingManager.disconnect()
    }

    private fun buildNotification(title: String, text: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(title)
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_upload)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            0,
            getString(R.string.stop),
            PendingIntent.getService(
                this,
                1,
                Intent(this, ContinuousTrackingForegroundService::class.java).apply {
                    action = ACTION_STOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun updateNotification(title: String, text: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, buildNotification(title = title, text = text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.continuous_service_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.continuous_service_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotificationTitle(
        progressState: com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState,
        isWorn: Boolean?
    ): String {
        return when {
            isWorn == false -> getString(R.string.continuous_service_paused_title)
            progressState == com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.Tracking -> {
                getString(R.string.continuous_service_running_title)
            }
            else -> getString(R.string.continuous_service_title)
        }
    }

    private fun buildNotificationText(
        connectionState: com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState,
        progressState: com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState,
        dataState: com.samsung.health.sensorsdksample.edatracking.data.ContinuousMonitoringData
    ): String {
        if (dataState.wearStatusSnapshot?.isWorn == false) {
            return getString(R.string.continuous_service_unworn_text)
        }

        val lastUpload = dataState.lastUploadedSnapshot?.uploadedAtMillis
        if (lastUpload != null) {
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = lastUpload
            }
            val timeText = String.format(
                java.util.Locale.getDefault(),
                "%02d:%02d:%02d",
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE),
                calendar.get(java.util.Calendar.SECOND)
            )
            return getString(R.string.continuous_service_last_upload_text, timeText)
        }

        return when {
            connectionState == com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState.Disconnected -> {
                getString(R.string.continuous_service_waiting_connection_text)
            }
            progressState == com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState.Tracking -> {
                getString(R.string.continuous_service_measuring_text)
            }
            else -> getString(R.string.continuous_service_text)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:ContinuousTracking"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun registerWearSensor() {
        if (sensorManager != null) {
            return
        }

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        wearSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT)
        if (wearSensor != null) {
            sensorManager?.registerListener(wearListener, wearSensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun registerBatteryReceiver() {
        if (batteryReceiverRegistered) {
            return
        }

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val initialIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(batteryStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(batteryStateReceiver, filter)
        }
        batteryReceiverRegistered = true
        handleBatteryIntent(initialIntent)
    }

    private fun unregisterBatteryReceiver() {
        if (!batteryReceiverRegistered) {
            return
        }

        unregisterReceiver(batteryStateReceiver)
        batteryReceiverRegistered = false
    }

    private fun handleBatteryIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val chargeSource = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "DOCK"
            else -> "BATTERY"
        }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val batteryLevelPercent = if (level >= 0 && scale > 0) {
            (level * 100) / scale
        } else {
            null
        }

        val shouldUpload = lastChargingState != isCharging ||
            lastChargeSource != chargeSource ||
            lastBatteryLevelPercent != batteryLevelPercent

        lastChargingState = isCharging
        lastChargeSource = chargeSource
        lastBatteryLevelPercent = batteryLevelPercent

        if (!shouldUpload) {
            return
        }

        publishLatestPowerState()
    }

    private fun publishLatestPowerState() {
        val isCharging = lastChargingState ?: return
        val chargeSource = lastChargeSource ?: return
        trackingManager.onPowerStateChanged(
            isCharging = isCharging,
            chargeSource = chargeSource,
            batteryLevelPercent = lastBatteryLevelPercent
        )
    }

    private fun unregisterWearSensor() {
        sensorManager?.unregisterListener(wearListener)
        wearSensor = null
        sensorManager = null
        lastWearState = null
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            return
        }

        unregisterReceiver(screenStateReceiver)
        screenReceiverRegistered = false
    }

    private fun ensureBeaconAdvertisingRunning() {
        startBeaconAdvertisingIfNeeded()
    }

    private fun startBeaconAdvertisingIfNeeded() {
        if (beaconAdvertiseCallback != null || beaconAdvertisingSetCallback != null) {
            return
        }

        try {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                Log.w(APP_TAG, "BLE advertising skipped: adapter unavailable or disabled")
                return
            }

            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.w(APP_TAG, "BLE advertising skipped: advertiser unavailable")
                return
            }

            val manufacturerData = buildIBeaconManufacturerData(
                uuid = beaconUuid,
                major = beaconMajor,
                minor = beaconMinor,
                measuredPower = beaconMeasuredPower
            )

            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addManufacturerData(IBEACON_MANUFACTURER_ID, manufacturerData)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val parameters = AdvertisingSetParameters.Builder()
                    .setLegacyMode(true)
                    .setConnectable(false)
                    .setScannable(false)
                    // ~100ms interval for more reliable RSSI sampling while moving.
                    .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .build()

                val callback = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(
                        advertisingSet: android.bluetooth.le.AdvertisingSet?,
                        txPower: Int,
                        status: Int
                    ) {
                        if (status == ADVERTISE_SUCCESS) {
                            Log.i(APP_TAG, "iBeacon advertising started (set), txPower=$txPower")
                        } else {
                            Log.w(APP_TAG, "iBeacon advertising failed (set), status=$status")
                        }
                    }

                    override fun onAdvertisingEnabled(advertisingSet: android.bluetooth.le.AdvertisingSet?, enable: Boolean, status: Int) {
                        Log.i(APP_TAG, "iBeacon advertising enabled=$enable status=$status")
                    }
                }

                beaconAdvertisingSetCallback = callback
                advertiser.startAdvertisingSet(
                    parameters,
                    advertiseData,
                    null,
                    null,
                    null,
                    0,
                    0,
                    callback
                )
            } else {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                    .setConnectable(false)
                    .build()

                val callback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                        Log.i(APP_TAG, "iBeacon advertising started")
                    }

                    override fun onStartFailure(errorCode: Int) {
                        Log.w(APP_TAG, "iBeacon advertising failed, errorCode=$errorCode")
                    }
                }

                beaconAdvertiseCallback = callback
                advertiser.startAdvertising(settings, advertiseData, callback)
            }
        } catch (e: SecurityException) {
            // Missing BLUETOOTH_ADVERTISE / BLUETOOTH_CONNECT runtime permission.
            Log.w(APP_TAG, "BLE advertising permission error", e)
        } catch (e: IllegalArgumentException) {
            Log.w(APP_TAG, "BLE advertising config error", e)
        }
    }

    private fun stopBeaconAdvertisingIfNeeded() {
        val advertiseCallback = beaconAdvertiseCallback
        val advertisingSetCallback = beaconAdvertisingSetCallback
        if (advertiseCallback == null && advertisingSetCallback == null) {
            return
        }

        try {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            val advertiser = adapter?.bluetoothLeAdvertiser
            if (advertiser == null) {
                beaconAdvertiseCallback = null
                beaconAdvertisingSetCallback = null
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && advertisingSetCallback != null) {
                advertiser.stopAdvertisingSet(advertisingSetCallback)
            }
            if (advertiseCallback != null) {
                advertiser.stopAdvertising(advertiseCallback)
            }
        } catch (e: SecurityException) {
            Log.w(APP_TAG, "BLE advertising stop permission error", e)
        } finally {
            beaconAdvertiseCallback = null
            beaconAdvertisingSetCallback = null
        }
    }

    private fun buildIBeaconManufacturerData(
        uuid: UUID,
        major: Int,
        minor: Int,
        measuredPower: Byte
    ): ByteArray {
        // iBeacon format (manufacturer-specific data):
        // 0x02 0x15 + 16B UUID + 2B major + 2B minor + 1B measured power
        val payload = ByteArray(23)
        payload[0] = 0x02
        payload[1] = 0x15

        val bb = ByteBuffer.wrap(ByteArray(16))
        bb.putLong(uuid.mostSignificantBits)
        bb.putLong(uuid.leastSignificantBits)
        val uuidBytes = bb.array()
        System.arraycopy(uuidBytes, 0, payload, 2, 16)

        payload[18] = ((major shr 8) and 0xFF).toByte()
        payload[19] = (major and 0xFF).toByte()
        payload[20] = ((minor shr 8) and 0xFF).toByte()
        payload[21] = (minor and 0xFF).toByte()
        payload[22] = measuredPower

        return payload
    }

    companion object {
        private const val IBEACON_MANUFACTURER_ID = 0x004C
        private const val NOTIFICATION_CHANNEL_ID = "continuous_tracking"
        private const val NOTIFICATION_ID = 3001
        private const val CHARGING_POWER_STATUS_INTERVAL_MILLIS = 10 * 60 * 1000L
        private const val ACTION_START = "com.samsung.health.sensorsdksample.edatracking.action.START_CONTINUOUS"
        private const val ACTION_STOP = "com.samsung.health.sensorsdksample.edatracking.action.STOP_CONTINUOUS"

        fun start(context: Context) {
            val intent = Intent(context, ContinuousTrackingForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ContinuousTrackingForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
