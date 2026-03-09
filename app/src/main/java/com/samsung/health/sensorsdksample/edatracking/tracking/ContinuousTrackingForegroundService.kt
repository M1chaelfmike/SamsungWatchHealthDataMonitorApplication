package com.samsung.health.sensorsdksample.edatracking.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
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
import javax.inject.Inject

@AndroidEntryPoint
class ContinuousTrackingForegroundService : Service() {

    @Inject
    lateinit var trackingManager: ContinuousTrackingManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var trackingCoordinatorJob: Job? = null
    private var notificationStateJob: Job? = null
    private var recoveryJob: Job? = null
    private var messageLogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var wearSensor: Sensor? = null
    private var lastWearState: Boolean? = null
    private var screenReceiverRegistered = false
    private val wearListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val isWorn = event.values.firstOrNull() == 1.0f
            if (lastWearState == isWorn) {
                return
            }
            lastWearState = isWorn
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTrackingService()
            else -> startTrackingService()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        trackingCoordinatorJob?.cancel()
        trackingCoordinatorJob = null
        notificationStateJob?.cancel()
        notificationStateJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        messageLogJob?.cancel()
        messageLogJob = null
        unregisterScreenReceiver()
        unregisterWearSensor()
        releaseWakeLock()
        trackingManager.stopTracking()
        trackingManager.disconnect()
        super.onDestroy()
    }

    private fun startTrackingService() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(
            title = getString(R.string.continuous_service_title),
            text = getString(R.string.continuous_service_text)
        ))
        acquireWakeLock()
        registerScreenReceiver()
        registerWearSensor()
        trackingManager.connect()
        if (trackingCoordinatorJob == null) {
            trackingCoordinatorJob = serviceScope.launch {
                trackingManager.connectionState.collectLatest { connectionState ->
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
        trackingCoordinatorJob?.cancel()
        trackingCoordinatorJob = null
        notificationStateJob?.cancel()
        notificationStateJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        messageLogJob?.cancel()
        messageLogJob = null
        unregisterScreenReceiver()
        unregisterWearSensor()
        releaseWakeLock()
        trackingManager.stopTracking()
        trackingManager.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "continuous_tracking"
        private const val NOTIFICATION_ID = 3001
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