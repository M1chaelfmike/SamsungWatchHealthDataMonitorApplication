package com.samsung.health.sensorsdksample.edatracking.tracking

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousMonitoringData
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState
import com.samsung.health.sensorsdksample.edatracking.data.EdaWindowLabel
import com.samsung.health.sensorsdksample.edatracking.data.EDAStatus
import com.samsung.health.sensorsdksample.edatracking.data.EDAValue
import com.samsung.health.sensorsdksample.edatracking.data.HeartRateValue
import com.samsung.health.sensorsdksample.edatracking.data.PpgValue
import com.samsung.health.sensorsdksample.edatracking.data.PowerStatusSnapshot
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempValue
import com.samsung.health.sensorsdksample.edatracking.data.UploadedSnapshot
import com.samsung.health.sensorsdksample.edatracking.data.WearStatusSnapshot
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity.Companion.APP_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class ContinuousTrackingManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val sharedServiceManager: SharedHealthTrackingServiceManager,
    private val trackerSessionCoordinator: TrackerSessionCoordinator
) {

    private val tempSamplingIntervalMillis = 10 * 60 * 1000L
    private val edaSamplingIntervalMillis = 10 * 60 * 1000L
    private val edaSamplingDurationMillis = 30_000L
    private val heartRateSamplingIntervalMillis = 5 * 60 * 1000L
    private val heartRateStabilizationDurationMillis = 10_000L
    private val ecgMeasurementDurationMillis = 30_000L
    private val ecgMeasurementTickMillis = 1_000L
    private val ecgLeadOffNoContact = 5
    private val heartRatePlausibleMinBpm = 35
    private val heartRatePlausibleMaxBpm = 220
    private val tempRetryDelayMillis = 15_000L
    private val sampleBufferRetentionMillis = 15_000L

    private val edaRetryDelayMillis = 15_000L
    private val tempSignalStaleRecoveryMillis = 30_000L
    private val tempStaleRecoveryMillis = 45_000L
    private val heartRateStaleRecoveryMillis = 120_000L
    private val edaStaleRecoveryMillis = 30_000L
    private val noDataStartupRecoveryMillis = 45_000L

    private val samplePollIntervalMillis = 250L
    private val FLUSH_INTERVAL_MILLIS = 1_000L
    private val pendingUploadQueueKey = "pending_upload_queue_v1"
    private val maxPendingUploadCount = 512
    private val preferences: SharedPreferences = context.getSharedPreferences("continuous_tracking", Context.MODE_PRIVATE)
    private var isPausedForOffBody = false

    private val legacyUploadHost = "192.168.0.8"
    private val legacyUploadPort = 8080
    private val defaultUploadHost = "192.168.0.5"
    private val defaultUploadPort = 3100

    private var edaTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var heartRateTracker: HealthTracker? = null
    private var ppgTracker: HealthTracker? = null
    private var ecgTracker: HealthTracker? = null
    private var hasConnectionLease = false
    private var hasTrackingOwnership = false
    private var heartRateLoopJob: Job? = null
    private var skinTempLoopJob: Job? = null
    private var edaLoopJob: Job? = null
    private var ecgMeasurementJob: Job? = null
    private val skinTempSamples = ArrayDeque<TimestampedSkinTempSample>()
    private val skinTempSamplesLock = Any()
    private val edaUploadMutex = Mutex()
    private val heartRateUploadMutex = Mutex()
    private val pendingUploadQueueMutex = Mutex()
    private val ecgSamplesLock = Any()
    private var skinTempAcquisitionStartedAtMillis: Long? = null
    private var heartRateAcquisitionStartedAtMillis: Long? = null
    private var edaAcquisitionStartedAtMillis: Long? = null
    private var nextSkinTempCycleAtMillis: Long = 0L
    private var nextEdaCycleAtMillis: Long = 0L
    private var nextHeartRateCycleAtMillis: Long = 0L
    private var skinTempCycleActive = false
    private var edaCycleActive = false
    private var heartRateCycleActive = false
    private var edaValidWindowStartedAtMillis: Long? = null
    private var heartRateValidWindowStartedAtMillis: Long? = null
    private var lastValidSkinTempAtMillis: Long? = null
    private var lastSkinTempSignalAtMillis: Long? = null
    private var lastQueuedHeartRateTimestamp: Long? = null
    private var lastQueuedEdaTimestamp: Long? = null
    private var trackingStartedAtMillis: Long? = null
    private var ecgSupported = false
    private var resumeContinuousTrackingAfterEcg = false
    private var skinTempSupportedForTracking = false
    private var heartRateSupportedForTracking = false
    private var edaSupportedForTracking = false
    private var skinTempCompletedForCurrentRun = false
    private var heartRateCompletedForCurrentRun = false
    private var edaCompletedForCurrentRun = false
    private val edaSessionLock = Any()
    private val heartRateSessionLock = Any()
    private val heartRateStableSamples = ArrayDeque<HeartRateValue>()
    private val ecgSamples = ArrayDeque<EcgSample>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _dataState = MutableStateFlow(
        ContinuousMonitoringData(
            uploadHost = loadUploadHost(),
            uploadPort = loadUploadPort()
        )
    )
    val dataState = _dataState.asStateFlow()

    private val _progressState = MutableStateFlow<ContinuousTrackingProgressState>(ContinuousTrackingProgressState.Idle)
    val progressState = _progressState.asStateFlow()

    private val _messageState = MutableSharedFlow<ContinuousTrackingMessageState>()
    val messageState = _messageState.asSharedFlow()

    private val _connectionState = MutableStateFlow<ContinuousConnectionState>(ContinuousConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    init {
        scope.launch {
            sharedServiceManager.isConnected.collect { isConnected ->
                _connectionState.value = if (isConnected) {
                    checkTrackerAvailability()
                    ContinuousConnectionState.Connected
                } else {
                    ecgSupported = false
                    updateTrackingUiFlags()
                    ContinuousConnectionState.Disconnected
                }
            }
        }

        scope.launch {
            sharedServiceManager.connectionErrors.collect { exception ->
                if (exception.hasResolution()) {
                    _messageState.emit(ContinuousTrackingMessageState.ResolvableError(exception))
                } else {
                    _messageState.emit(ContinuousTrackingMessageState.Error(exception.message))
                }
            }
        }
    }

    private val edaTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            if (!edaCycleActive) {
                return
            }

            val validSamples = data
                .map(::extractEdaValues)
                .filter(::isValidEdaValue)

            val newSamples = reserveNewEdaSamples(validSamples)
            if (newSamples.isNotEmpty()) {
                startEdaCollectionWindowIfNeeded()
                scope.launch {
                    processValidEdaSamples(newSamples)
                }
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "EDA data flushed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            stopEdaTracker()
            if (error == HealthTracker.TrackerError.PERMISSION_ERROR) {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.PermissionError)
                }
            } else {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.Error(error.name))
                }
            }
        }
    }

    private val skinTempTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            data.lastOrNull()?.let { dataPoint ->
                val extractedValue = extractSkinTempValues(dataPoint)
                Log.i(
                    APP_TAG,
                    "Skin temp sample received: status=${extractedValue.status}, wrist=${extractedValue.wristSkinTemperature}, ambient=${extractedValue.ambientTemperature}"
                )
                lastSkinTempSignalAtMillis = System.currentTimeMillis()
                appendSkinTempSample(extractedValue)
                if (isValidSkinTempValue(extractedValue)) {
                    onValidSkinTempValue(extractedValue)
                }
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "Skin temperature data flushed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            stopSkinTempTracker()
            if (error == HealthTracker.TrackerError.PERMISSION_ERROR) {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.PermissionError)
                }
            } else {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.Error(error.name))
                }
            }
        }
    }

    private val heartRateTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            val samples = data.map(::extractHeartRateValues)
            samples.lastOrNull()?.let { latestSample ->
                Log.i(
                    APP_TAG,
                    "Heart rate sample received: hr=${latestSample.heartRate}, status=${latestSample.status}, timestamp=${latestSample.timestamp}"
                )
                _dataState.value = _dataState.value.copy(liveHeartRateValue = latestSample)
            }

            if (!heartRateCycleActive) {
                return
            }

            val validSamples = reserveNewHeartRateSamples(samples.filter(::isValidHeartRateValue))
            if (validSamples.isNotEmpty()) {
                bufferHeartRateSamples(validSamples)
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "Heart rate data flushed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            stopHeartRateTracker()
            if (error == HealthTracker.TrackerError.PERMISSION_ERROR) {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.PermissionError)
                }
            } else {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.Error(error.name))
                }
            }
        }
    }

    private val ppgTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            data.lastOrNull()?.let { dataPoint ->
                _dataState.value = _dataState.value.copy(ppgValue = extractPpgValues(dataPoint))
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "PPG data flushed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            if (error == HealthTracker.TrackerError.PERMISSION_ERROR) {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.PermissionError)
                }
            } else {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.Error(error.name))
                }
            }
        }
    }

    private val ecgTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            if (!_dataState.value.ecgMeasurementRunning || data.isEmpty()) {
                return
            }

            var leadOffDetected = false
            val newSamples = mutableListOf<EcgSample>()
            data.forEach { dataPoint ->
                val leadOff = dataPoint.getValue(ValueKey.EcgSet.LEAD_OFF)
                if (leadOff == ecgLeadOffNoContact) {
                    leadOffDetected = true
                } else {
                    val valueMv = dataPoint.getValue(ValueKey.EcgSet.ECG_MV)
                    newSamples += EcgSample(
                        timestamp = dataPoint.timestamp,
                        valueMv = valueMv
                    )
                }
            }

            if (newSamples.isNotEmpty()) {
                synchronized(ecgSamplesLock) {
                    ecgSamples.addAll(newSamples)
                }
            }

            val latestValue = newSamples.lastOrNull()?.valueMv ?: _dataState.value.ecgCurrentValueMv
            _dataState.value = _dataState.value.copy(
                ecgLeadOff = leadOffDetected,
                ecgCurrentValueMv = latestValue,
                ecgStatusText = if (leadOffDetected) {
                    "Keep finger on sensor"
                } else {
                    latestValue?.let { String.format(Locale.getDefault(), "%.2f mV", it) } ?: "Measuring"
                }
            )
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "ECG data flushed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.i(APP_TAG, "ECG onError called: $error")
            scope.launch {
                finishEcgMeasurement(success = false, errorMessage = error.name)
            }
        }
    }

    private fun updateTrackingUiFlags() {
        _dataState.value = _dataState.value.copy(
            ecgSupported = ecgSupported,
            isAnySensorCycleActive = skinTempCycleActive || heartRateCycleActive || edaCycleActive,
            isEcgReadyToStart = isEcgReadyToStart()
        )
    }

    private fun isEcgReadyToStart(): Boolean {
        if (_progressState.value != ContinuousTrackingProgressState.Tracking) {
            return true
        }

        val skinTempReady = !skinTempSupportedForTracking || skinTempCompletedForCurrentRun
        val heartRateReady = !heartRateSupportedForTracking || heartRateCompletedForCurrentRun
        val edaReady = !edaSupportedForTracking || edaCompletedForCurrentRun
        return skinTempReady && heartRateReady && edaReady &&
            !skinTempCycleActive && !heartRateCycleActive && !edaCycleActive
    }

    private fun resetPrimarySensorCompletionState() {
        skinTempCompletedForCurrentRun = false
        heartRateCompletedForCurrentRun = false
        edaCompletedForCurrentRun = false
    }

    private fun updateSupportedPrimaryTrackers(availableTrackers: Collection<HealthTrackerType>) {
        edaSupportedForTracking = availableTrackers.contains(HealthTrackerType.EDA_CONTINUOUS)
        skinTempSupportedForTracking = availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
        heartRateSupportedForTracking = availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
    }

    fun connect() {
        if (hasConnectionLease) {
            return
        }

        try {
            sharedServiceManager.acquireConnection()
            hasConnectionLease = true
        } catch (exception: Exception) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error(exception.message))
            }
        }
    }

    fun disconnect() {
        stopTracking()
        if (hasConnectionLease) {
            sharedServiceManager.releaseConnection()
            hasConnectionLease = false
        }
        if (_progressState.value != ContinuousTrackingProgressState.TrackingDisabled) {
            _progressState.value = ContinuousTrackingProgressState.Idle
        }
    }

    fun startTracking() {
        if (_progressState.value == ContinuousTrackingProgressState.Tracking) {
            return
        }

        if (!sharedServiceManager.isConnected.value) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Health Tracking Service is not connected"))
            }
            return
        }

        val availableTrackers = sharedServiceManager.getSupportedTrackers().orEmpty()
        updateSupportedPrimaryTrackers(availableTrackers)
        val edaSupported = availableTrackers.contains(HealthTrackerType.EDA_CONTINUOUS)
        val skinTempSupported = availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
        val heartRateSupported = availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
        val ppgSupported = availableTrackers.contains(HealthTrackerType.PPG_CONTINUOUS)
        val hasPrimarySensorSupported = edaSupported || skinTempSupported || heartRateSupported

        if (!hasPrimarySensorSupported) {
            _progressState.value = ContinuousTrackingProgressState.TrackingDisabled
            scope.launch {
                _messageState.emit(
                    ContinuousTrackingMessageState.UnsupportedSensors(
                        edaSupported = edaSupported,
                        skinTemperatureSupported = skinTempSupported,
                        heartRateSupported = heartRateSupported,
                        ppgSupported = ppgSupported
                    )
                )
            }
            return
        }

        hasTrackingOwnership = false
        if (edaSupported) {
            hasTrackingOwnership = trackerSessionCoordinator.tryAcquireEda(TrackerOwner.PAGE3_CONTINUOUS)
            if (!hasTrackingOwnership) {
                scope.launch {
                    _messageState.emit(ContinuousTrackingMessageState.TrackingInUse)
                }
            }
        }

        cancelSensorLoops()
        trackingStartedAtMillis = System.currentTimeMillis()
        resetPrimarySensorCompletionState()
        edaAcquisitionStartedAtMillis = null
        heartRateAcquisitionStartedAtMillis = null
        skinTempAcquisitionStartedAtMillis = null
        nextSkinTempCycleAtMillis = trackingStartedAtMillis ?: 0L
        nextEdaCycleAtMillis = trackingStartedAtMillis ?: 0L
        nextHeartRateCycleAtMillis = trackingStartedAtMillis ?: 0L
        skinTempCycleActive = false
        edaCycleActive = false
        heartRateCycleActive = false
        updateTrackingUiFlags()
        edaValidWindowStartedAtMillis = null
        heartRateValidWindowStartedAtMillis = null
        clearHeartRateStableSamples()
        lastQueuedEdaTimestamp = null
        lastQueuedHeartRateTimestamp = null
        _progressState.value = ContinuousTrackingProgressState.Tracking
        if (edaSupported && hasTrackingOwnership) {
            edaLoopJob = scope.launch { runEdaLoop() }
        }
        if (skinTempSupported) {
            skinTempLoopJob = scope.launch { runSkinTempLoop() }
        }
        if (heartRateSupported) {
            heartRateLoopJob = scope.launch { runHeartRateLoop() }
        }
    }

    fun stopTracking() {
        cancelSensorLoops()
        stopAllTrackers()
        skinTempAcquisitionStartedAtMillis = null
        edaAcquisitionStartedAtMillis = null
        heartRateAcquisitionStartedAtMillis = null
        lastQueuedEdaTimestamp = null
        lastQueuedHeartRateTimestamp = null
        trackingStartedAtMillis = null
        resetPrimarySensorCompletionState()
        nextSkinTempCycleAtMillis = 0L
        nextEdaCycleAtMillis = 0L
        nextHeartRateCycleAtMillis = 0L
        skinTempCycleActive = false
        edaCycleActive = false
        heartRateCycleActive = false
        updateTrackingUiFlags()
        edaValidWindowStartedAtMillis = null
        heartRateValidWindowStartedAtMillis = null
        clearHeartRateStableSamples()
        _dataState.value = _dataState.value.copy(
            liveHeartRateValue = null,
            heartRateValue = null,
            lastHeartRateUpdateAtMillis = null,
            skinTempValue = null,
            lastSkinTempUpdateAtMillis = null,
            edaValue = null,
            edaLabel = null,
            edaValidSampleCount = 0,
            lastEdaUpdateAtMillis = null,
            lastAcquiredSensor = null,
            lastAcquisitionElapsedMillis = null,
            lastAcquisitionAtMillis = null
        )
        lastValidSkinTempAtMillis = null
        lastSkinTempSignalAtMillis = null
        if (_progressState.value == ContinuousTrackingProgressState.Tracking) {
            _progressState.value = ContinuousTrackingProgressState.Idle
        }
        if (hasTrackingOwnership) {
            trackerSessionCoordinator.releaseEda(TrackerOwner.PAGE3_CONTINUOUS)
            hasTrackingOwnership = false
        }
    }

    fun onWearStateChanged(isWorn: Boolean) {
        val changedAtMillis = System.currentTimeMillis()
        val powerSnapshot = _dataState.value.powerStatusSnapshot
        _dataState.value = _dataState.value.copy(
            wearStatusSnapshot = WearStatusSnapshot(
                isWorn = isWorn,
                isCharging = powerSnapshot?.isCharging,
                chargeSource = powerSnapshot?.chargeSource,
                batteryLevelPercent = powerSnapshot?.batteryLevelPercent,
                changedAtMillis = changedAtMillis
            )
        )

        scope.launch {
            try {
                uploadWearStatus(isWorn = isWorn, changedAtMillis = changedAtMillis)
            } catch (exception: Exception) {
                Log.w(APP_TAG, "Failed to upload wear status", exception)
            }
        }

        if (isWorn) {
            if (isPausedForOffBody && _connectionState.value == ContinuousConnectionState.Connected) {
                isPausedForOffBody = false
                startTracking()
            }
        } else {
            if (_progressState.value == ContinuousTrackingProgressState.Tracking) {
                isPausedForOffBody = true
                stopTracking()
            }
        }
    }

    fun onPowerStateChanged(
        isCharging: Boolean,
        chargeSource: String,
        batteryLevelPercent: Int?
    ) {
        val changedAtMillis = System.currentTimeMillis()
        val currentWearSnapshot = _dataState.value.wearStatusSnapshot
        _dataState.value = _dataState.value.copy(
            powerStatusSnapshot = PowerStatusSnapshot(
                isCharging = isCharging,
                chargeSource = chargeSource,
                batteryLevelPercent = batteryLevelPercent,
                changedAtMillis = changedAtMillis
            ),
            wearStatusSnapshot = currentWearSnapshot?.copy(
                isCharging = isCharging,
                chargeSource = chargeSource,
                batteryLevelPercent = batteryLevelPercent,
                changedAtMillis = changedAtMillis
            )
        )

        scope.launch {
            try {
                uploadPowerStatus(
                    isCharging = isCharging,
                    chargeSource = chargeSource,
                    batteryLevelPercent = batteryLevelPercent,
                    changedAtMillis = changedAtMillis,
                    isWorn = currentWearSnapshot?.isWorn
                )
            } catch (exception: Exception) {
                Log.w(APP_TAG, "Failed to upload power status", exception)
            }
        }
    }

    fun updateUploadTarget(host: String, port: Int) {
        val normalizedHost = host.trim().ifEmpty { defaultUploadHost }
        val normalizedPort = port.coerceIn(1, 65535)
        preferences.edit()
            .putString("upload_host", normalizedHost)
            .putInt("upload_port", normalizedPort)
            .apply()
        _dataState.value = _dataState.value.copy(
            uploadHost = normalizedHost,
            uploadPort = normalizedPort
        )
    }

    fun toggleEcgMeasurement() {
        if (_dataState.value.ecgMeasurementRunning) {
            scope.launch {
                finishEcgMeasurement(success = false, errorMessage = "Cancelled")
            }
            return
        }

        if (!ecgSupported) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("ECG tracker is not supported"))
            }
            return
        }

        if (_connectionState.value != ContinuousConnectionState.Connected) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Health Tracking Service is not connected"))
            }
            return
        }

        if (skinTempCycleActive || heartRateCycleActive || edaCycleActive) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("HR/TEMP/EDA 活动中，暂时不能开始 ECG"))
            }
            return
        }

        if (!isEcgReadyToStart()) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("请等待 HR/TEMP/EDA 本轮全部完成后再开始 ECG"))
            }
            return
        }

        startEcgMeasurement()
    }

    private fun startEcgMeasurement() {
        val tracker = sharedServiceManager.getHealthTracker(HealthTrackerType.ECG_ON_DEMAND)
        if (tracker == null) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("ECG tracker is not initialized"))
            }
            return
        }

        pauseContinuousTrackingForEcg()
        clearEcgSamples()
        ecgTracker = tracker
        _dataState.value = _dataState.value.copy(
            ecgMeasurementRunning = true,
            ecgLeadOff = true,
            ecgRemainingSeconds = (ecgMeasurementDurationMillis / 1000L).toInt(),
            ecgCurrentValueMv = null,
            ecgStatusText = "Measuring ECG"
        )

        try {
            ecgTracker?.setEventListener(ecgTrackerListener)
        } catch (exception: Exception) {
            stopEcgTracker()
            _dataState.value = _dataState.value.copy(
                ecgMeasurementRunning = false,
                ecgStatusText = "ECG unavailable"
            )
            resumeContinuousTrackingAfterEcg()
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error(exception.message))
            }
            return
        }

        ecgMeasurementJob?.cancel()
        ecgMeasurementJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startedAt
                val remainingSeconds = ((ecgMeasurementDurationMillis - elapsed).coerceAtLeast(0L) / 1000L).toInt()
                if (!_dataState.value.ecgMeasurementRunning) {
                    return@launch
                }
                _dataState.value = _dataState.value.copy(ecgRemainingSeconds = remainingSeconds)
                if (elapsed >= ecgMeasurementDurationMillis) {
                    break
                }
                delay(ecgMeasurementTickMillis)
            }
            finishEcgMeasurement(success = true, errorMessage = null)
        }
    }

    private fun pauseContinuousTrackingForEcg() {
        resumeContinuousTrackingAfterEcg = _progressState.value == ContinuousTrackingProgressState.Tracking
        if (!resumeContinuousTrackingAfterEcg) {
            return
        }

        cancelSensorLoops()
        stopAllTrackers()
        skinTempCycleActive = false
        edaCycleActive = false
        heartRateCycleActive = false
        edaValidWindowStartedAtMillis = null
        heartRateValidWindowStartedAtMillis = null
        clearHeartRateStableSamples()
        clearSkinTempSamples()
        updateTrackingUiFlags()
    }

    private fun resumeContinuousTrackingAfterEcg() {
        if (!resumeContinuousTrackingAfterEcg) {
            return
        }

        val availableTrackers = sharedServiceManager.getSupportedTrackers().orEmpty()
        resetPrimarySensorCompletionState()
        val now = System.currentTimeMillis()
        nextSkinTempCycleAtMillis = now
        nextEdaCycleAtMillis = now
        nextHeartRateCycleAtMillis = now

        if (availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS) && skinTempLoopJob == null) {
            skinTempLoopJob = scope.launch { runSkinTempLoop() }
        }
        if (availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS) && heartRateLoopJob == null) {
            heartRateLoopJob = scope.launch { runHeartRateLoop() }
        }
        if (availableTrackers.contains(HealthTrackerType.EDA_CONTINUOUS) && hasTrackingOwnership && edaLoopJob == null) {
            edaLoopJob = scope.launch { runEdaLoop() }
        }

        resumeContinuousTrackingAfterEcg = false
        updateTrackingUiFlags()
    }

    private suspend fun finishEcgMeasurement(success: Boolean, errorMessage: String?) {
        val currentJob = currentCoroutineContext()[Job]
        if (ecgMeasurementJob != null && ecgMeasurementJob !== currentJob) {
            ecgMeasurementJob?.cancel()
        }
        ecgMeasurementJob = null
        stopEcgTracker()

        val samples = snapshotEcgSamples()
        val latestValue = samples.lastOrNull()?.valueMv
        val leadOff = _dataState.value.ecgLeadOff
        val canUpload = success && !leadOff && samples.isNotEmpty()
        var statusText = when {
            errorMessage == "Cancelled" -> "ECG cancelled"
            !success -> "ECG failed"
            leadOff -> "Measurement failed"
            else -> latestValue?.let { String.format(Locale.getDefault(), "Done %.2f mV", it) } ?: "Measurement successful"
        }

        if (canUpload) {
            val payload = buildEcgPayload(samples)
            try {
                uploadPayloadNow(payload)
                val uploadedAtMillis = payload.getLong("timestamp")
                statusText = latestValue?.let {
                    String.format(Locale.getDefault(), "Sent %.2f mV", it)
                } ?: "ECG sent"
                _dataState.value = _dataState.value.copy(
                    lastUploadedSnapshot = UploadedSnapshot(
                        uploadedAtMillis = uploadedAtMillis,
                        sensorType = "ECG",
                        primaryText = "${samples.size} samples",
                        secondaryText = latestValue?.let { String.format(Locale.getDefault(), "%.2f mV", it) }
                    )
                )
            } catch (exception: Exception) {
                enqueuePendingUpload(sensorName = "ECG", payload = payload)
                handleUploadFailure("ECG", exception)
                statusText = "ECG saved for retry"
            }
        }

        _dataState.value = _dataState.value.copy(
            ecgMeasurementRunning = false,
            ecgRemainingSeconds = null,
            ecgStatusText = statusText,
            ecgCurrentValueMv = latestValue,
            lastEcgValueMv = latestValue,
            lastEcgMeasuredAtMillis = if (canUpload || success) System.currentTimeMillis() else _dataState.value.lastEcgMeasuredAtMillis,
            lastEcgSampleCount = samples.size
        )

        clearEcgSamples()
        resumeContinuousTrackingAfterEcg()
    }

    fun reconnect() {
        if (hasConnectionLease) {
            sharedServiceManager.releaseConnection()
            hasConnectionLease = false
        }
        stopAllTrackers()
        connect()
    }

    fun recoverTracking(reason: String) {
        Log.w(APP_TAG, "Recover tracking: $reason")
        if (_connectionState.value != ContinuousConnectionState.Connected || isPausedForOffBody) {
            return
        }
        stopTracking()
        startTracking()
    }

    fun recoveryReason(nowMillis: Long = System.currentTimeMillis()): String? {
        if (_progressState.value != ContinuousTrackingProgressState.Tracking) {
            return null
        }

        val snapshot = _dataState.value
        val trackingStartedAt = trackingStartedAtMillis
        if (trackingStartedAt != null &&
            snapshot.lastAcquisitionAtMillis == null &&
            nowMillis - trackingStartedAt >= noDataStartupRecoveryMillis
        ) {
            return "no data after ${formatElapsedDuration(nowMillis - trackingStartedAt)}"
        }

        snapshot.lastSkinTempUpdateAtMillis?.let { lastTempAt ->
            if (nowMillis - lastTempAt >= tempStaleRecoveryMillis) {
                return "temperature stalled for ${formatElapsedDuration(nowMillis - lastTempAt)}"
            }
        }

        lastSkinTempSignalAtMillis?.let { lastTempSignalAt ->
            if (skinTempTracker != null && nowMillis - lastTempSignalAt >= tempSignalStaleRecoveryMillis) {
                return "temperature callback silent for ${formatElapsedDuration(nowMillis - lastTempSignalAt)}"
            }
        }

        snapshot.lastHeartRateUpdateAtMillis?.let { lastHeartRateAt ->
            if (nowMillis - lastHeartRateAt >= heartRateStaleRecoveryMillis) {
                return "heart rate stalled for ${formatElapsedDuration(nowMillis - lastHeartRateAt)}"
            }
        }

        snapshot.lastEdaUpdateAtMillis?.let { lastEdaAt ->
            if (nowMillis - lastEdaAt >= edaStaleRecoveryMillis) {
                return "EDA stalled for ${formatElapsedDuration(nowMillis - lastEdaAt)}"
            }
        }

        return null
    }

    private fun checkTrackerAvailability() {
        val availableTrackers = sharedServiceManager.getSupportedTrackers()
        updateSupportedPrimaryTrackers(availableTrackers.orEmpty())
        val edaSupported = availableTrackers?.contains(HealthTrackerType.EDA_CONTINUOUS) == true
        val skinTempSupported = availableTrackers?.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS) == true
        val heartRateSupported = availableTrackers?.contains(HealthTrackerType.HEART_RATE_CONTINUOUS) == true
        val ppgSupported = availableTrackers?.contains(HealthTrackerType.PPG_CONTINUOUS) == true
        ecgSupported = availableTrackers?.contains(HealthTrackerType.ECG_ON_DEMAND) == true
        val hasPrimarySensorSupported = edaSupported || skinTempSupported || heartRateSupported
        updateTrackingUiFlags()

        if (!hasPrimarySensorSupported) {
            _progressState.value = ContinuousTrackingProgressState.TrackingDisabled
            scope.launch {
                _messageState.emit(
                    ContinuousTrackingMessageState.UnsupportedSensors(
                        edaSupported = edaSupported,
                        skinTemperatureSupported = skinTempSupported,
                        heartRateSupported = heartRateSupported,
                        ppgSupported = ppgSupported
                    )
                )
            }
        } else if (_progressState.value == ContinuousTrackingProgressState.TrackingDisabled) {
            _progressState.value = ContinuousTrackingProgressState.Idle
        }
    }

    private suspend fun runSkinTempLoop() {
        var lastFlushAtMillis = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (!skinTempCycleActive) {
                val waitMillis = nextSkinTempCycleAtMillis - now
                if (waitMillis > 0L) {
                    delay(minOf(waitMillis, 1_000L))
                    continue
                }

                skinTempCycleActive = true
                updateTrackingUiFlags()
                skinTempAcquisitionStartedAtMillis = now
                lastFlushAtMillis = 0L
                Log.i(APP_TAG, "Start scheduled TEMP acquisition")
            }

            if (!ensureSkinTempTrackerRunning()) {
                delay(tempRetryDelayMillis)
                continue
            }

            if (now - lastFlushAtMillis >= FLUSH_INTERVAL_MILLIS) {
                flushTracker(skinTempTracker, "TEMP")
                lastFlushAtMillis = now
            }

            delay(samplePollIntervalMillis)
        }
    }

    private suspend fun runHeartRateLoop() {
        var lastFlushAtMillis = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (!heartRateCycleActive) {
                val waitMillis = nextHeartRateCycleAtMillis - now
                if (waitMillis > 0L) {
                    delay(minOf(waitMillis, 1_000L))
                    continue
                }

                heartRateCycleActive = true
                updateTrackingUiFlags()
                heartRateAcquisitionStartedAtMillis = now
                heartRateValidWindowStartedAtMillis = null
                clearHeartRateStableSamples()
                lastFlushAtMillis = 0L
                Log.i(APP_TAG, "Start scheduled HR acquisition")
            }

            if (!ensureHeartRateTrackerRunning()) {
                delay(tempRetryDelayMillis)
                continue
            }

            if (now - lastFlushAtMillis >= FLUSH_INTERVAL_MILLIS) {
                flushTracker(heartRateTracker, "HR")
                lastFlushAtMillis = now
            }

            val stableWindowStartedAt = synchronized(heartRateSessionLock) {
                heartRateValidWindowStartedAtMillis
            }
            if (stableWindowStartedAt != null && now - stableWindowStartedAt >= heartRateStabilizationDurationMillis) {
                val stableHeartRate = buildStableHeartRateValue(snapshotHeartRateStableSamples())
                completeHeartRateCycle(stableHeartRate, now)
                continue
            }

            delay(samplePollIntervalMillis)
        }
    }

    private suspend fun runEdaLoop() {
        var lastFlushAtMillis = 0L
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            if (!edaCycleActive) {
                val waitMillis = nextEdaCycleAtMillis - now
                if (waitMillis > 0L) {
                    delay(minOf(waitMillis, 1_000L))
                    continue
                }

                edaCycleActive = true
                updateTrackingUiFlags()
                edaValidWindowStartedAtMillis = null
                edaAcquisitionStartedAtMillis = null
                lastFlushAtMillis = 0L
                Log.i(APP_TAG, "Start scheduled EDA acquisition")
            }

            if (!ensureEdaTrackerRunning()) {
                delay(edaRetryDelayMillis)
                continue
            }

            if (now - lastFlushAtMillis >= FLUSH_INTERVAL_MILLIS) {
                flushTracker(edaTracker, "EDA")
                lastFlushAtMillis = now
            }

            val validWindowStartedAt = synchronized(edaSessionLock) {
                edaValidWindowStartedAtMillis
            }
            if (validWindowStartedAt != null && now - validWindowStartedAt >= edaSamplingDurationMillis) {
                completeEdaCycle(now)
                continue
            }

            delay(samplePollIntervalMillis)
        }
    }

    private fun flushTracker(tracker: HealthTracker?, sensorTag: String) {
        try {
            tracker?.flush()
            Log.d(APP_TAG, "$sensorTag tracker flush() called")
        } catch (exception: Exception) {
            Log.w(APP_TAG, "$sensorTag tracker flush() failed", exception)
        }
    }

    private fun onValidSkinTempValue(value: SkinTempValue) {
        if (!skinTempCycleActive) {
            return
        }

        val now = System.currentTimeMillis()
        val startedAtMillis = skinTempAcquisitionStartedAtMillis ?: now
        val elapsedMillis = (now - startedAtMillis).coerceAtLeast(0L)
        val sincePreviousMillis = lastValidSkinTempAtMillis?.let { (now - it).coerceAtLeast(0L) }
        lastValidSkinTempAtMillis = now
        skinTempAcquisitionStartedAtMillis = now
        _dataState.value = _dataState.value.copy(
            skinTempValue = value,
            lastSkinTempUpdateAtMillis = now
        )
        recordAcquisition(sensor = "TEMP", elapsedMillis = elapsedMillis)
        scope.launch {
            val wristText = value.wristSkinTemperature?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "n/a"
            val ambientText = value.ambientTemperature?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "n/a"
            val sincePreviousText = sincePreviousMillis?.let { formatElapsedDuration(it) } ?: "首次合法值"
            _messageState.emit(
                ContinuousTrackingMessageState.Info(
                    "TEMP获取成功 WS=$wristText°C AT=$ambientText°C，用时 ${formatElapsedDuration(elapsedMillis)}，距上次 $sincePreviousText"
                )
            )
        }
        uploadTemperatureValue(value)
        skinTempCompletedForCurrentRun = true
        skinTempCycleActive = false
        updateTrackingUiFlags()
        nextSkinTempCycleAtMillis = now + tempSamplingIntervalMillis
        clearSkinTempSamples()
        stopSkinTempTracker()
    }

    private fun startEdaCollectionWindowIfNeeded() {
        synchronized(edaSessionLock) {
            if (edaValidWindowStartedAtMillis == null) {
                val now = System.currentTimeMillis()
                edaValidWindowStartedAtMillis = now
                edaAcquisitionStartedAtMillis = now
            }
        }
    }

    private fun bufferHeartRateSamples(samples: List<HeartRateValue>) {
        synchronized(heartRateSessionLock) {
            if (heartRateValidWindowStartedAtMillis == null) {
                val now = System.currentTimeMillis()
                heartRateValidWindowStartedAtMillis = now
                heartRateAcquisitionStartedAtMillis = now
                heartRateStableSamples.clear()
            }
            heartRateStableSamples.addAll(samples)
        }
    }

    private fun snapshotHeartRateStableSamples(): List<HeartRateValue> {
        return synchronized(heartRateSessionLock) {
            heartRateStableSamples.toList()
        }
    }

    private fun clearHeartRateStableSamples() {
        synchronized(heartRateSessionLock) {
            heartRateStableSamples.clear()
        }
    }

    private suspend fun completeHeartRateCycle(stableHeartRate: HeartRateValue?, now: Long) {
        val startedAtMillis = heartRateAcquisitionStartedAtMillis ?: now
        val elapsedMillis = (now - startedAtMillis).coerceAtLeast(0L)

        if (stableHeartRate != null) {
            heartRateUploadMutex.withLock {
                val payload = buildHeartRatePayload(stableHeartRate)
                try {
                    uploadPayloadNow(payload)
                    val uploadedAtMillis = payload.getLong("timestamp")
                    replayPendingUploads(trigger = "heart_rate_cycle")
                    _dataState.value = _dataState.value.copy(
                        heartRateValue = stableHeartRate,
                        liveHeartRateValue = stableHeartRate,
                        lastHeartRateUpdateAtMillis = now,
                        lastUploadedSnapshot = UploadedSnapshot(
                            uploadedAtMillis = uploadedAtMillis,
                            sensorType = "HR",
                            primaryText = "${stableHeartRate.heartRate ?: 0} bpm",
                            secondaryText = "stable 10s"
                        )
                    )
                    recordAcquisition(sensor = "HR", elapsedMillis = elapsedMillis)
                    _messageState.emit(
                        ContinuousTrackingMessageState.Info(
                            "HR获取稳定值 ${stableHeartRate.heartRate ?: 0} bpm，用时 ${formatElapsedDuration(elapsedMillis)}，已发送"
                        )
                    )
                } catch (exception: Exception) {
                    enqueuePendingUpload(sensorName = "心率", payload = payload)
                    handleUploadFailure("心率", exception)
                }
            }
        } else {
            _messageState.emit(
                ContinuousTrackingMessageState.Info(
                    "HR在10秒稳定窗口内未形成合理值，本轮不发送"
                )
            )
        }

        heartRateCompletedForCurrentRun = true
        heartRateCycleActive = false
        updateTrackingUiFlags()
        nextHeartRateCycleAtMillis = now + heartRateSamplingIntervalMillis
        heartRateValidWindowStartedAtMillis = null
        clearHeartRateStableSamples()
        stopHeartRateTracker()
    }

    private fun completeEdaCycle(now: Long) {
        edaCompletedForCurrentRun = true
        edaCycleActive = false
        updateTrackingUiFlags()
        nextEdaCycleAtMillis = now + edaSamplingIntervalMillis
        synchronized(edaSessionLock) {
            edaValidWindowStartedAtMillis = null
        }
        stopEdaTracker()
    }

    private fun ensureEdaTrackerRunning(): Boolean {
        if (edaTracker != null) {
            return true
        }

        edaTracker = sharedServiceManager.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)
        if (edaTracker == null) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("EDA tracker is not initialized"))
            }
            return false
        }

        return try {
            edaTracker?.setEventListener(edaTrackerListener)
            if (edaAcquisitionStartedAtMillis == null) {
                edaAcquisitionStartedAtMillis = System.currentTimeMillis()
            }
            true
        } catch (exception: IllegalStateException) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("EDA tracker is not ready"))
            }
            stopEdaTracker()
            false
        }
    }

    private fun ensureSkinTempTrackerRunning(): Boolean {
        clearSkinTempSamples()
        if (skinTempTracker != null) {
            return true
        }

        skinTempTracker = sharedServiceManager.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
        if (skinTempTracker == null) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Skin temperature tracker is not initialized"))
            }
            return false
        }

        return try {
            skinTempAcquisitionStartedAtMillis = System.currentTimeMillis()
            skinTempTracker?.setEventListener(skinTempTrackerListener)
            true
        } catch (exception: IllegalStateException) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Skin temperature tracker is not ready"))
            }
            stopSkinTempTracker()
            false
        }
    }

    private fun ensureHeartRateTrackerRunning(): Boolean {
        if (heartRateTracker != null) {
            return true
        }

        heartRateTracker = sharedServiceManager.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        if (heartRateTracker == null) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Heart rate tracker is not initialized"))
            }
            return false
        }

        return try {
            heartRateTracker?.setEventListener(heartRateTrackerListener)
            if (heartRateAcquisitionStartedAtMillis == null) {
                heartRateAcquisitionStartedAtMillis = System.currentTimeMillis()
            }
            true
        } catch (exception: IllegalStateException) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Heart rate tracker is not ready"))
            }
            stopHeartRateTracker()
            false
        }
    }

    private fun cancelSensorLoops() {
        skinTempLoopJob?.cancel()
        skinTempLoopJob = null
        heartRateLoopJob?.cancel()
        heartRateLoopJob = null
        edaLoopJob?.cancel()
        edaLoopJob = null
    }

    private fun stopAllTrackers() {
        stopEdaTracker()
        stopSkinTempTracker()
        stopHeartRateTracker()
        stopEcgTracker()
        ppgTracker?.unsetEventListener()
        ppgTracker = null
    }

    private fun stopEdaTracker() {
        edaTracker?.unsetEventListener()
        edaTracker = null
        edaAcquisitionStartedAtMillis = null
    }

    private fun stopSkinTempTracker() {
        skinTempTracker?.unsetEventListener()
        skinTempTracker = null
        skinTempAcquisitionStartedAtMillis = null
    }

    private fun stopHeartRateTracker() {
        heartRateTracker?.unsetEventListener()
        heartRateTracker = null
        heartRateAcquisitionStartedAtMillis = null
    }

    private fun stopEcgTracker() {
        ecgTracker?.unsetEventListener()
        ecgTracker = null
    }

    private fun appendSkinTempSample(sample: SkinTempValue) {
        val receivedAtMillis = System.currentTimeMillis()
        synchronized(skinTempSamplesLock) {
            skinTempSamples.addLast(TimestampedSkinTempSample(sample = sample, receivedAtMillis = receivedAtMillis))
            trimExpiredSamples(skinTempSamples, receivedAtMillis) { it.receivedAtMillis }
        }
    }

    private fun clearSkinTempSamples() {
        synchronized(skinTempSamplesLock) {
            skinTempSamples.clear()
        }
    }

    private fun snapshotSkinTempSamples(): List<TimestampedSkinTempSample> {
        return synchronized(skinTempSamplesLock) {
            skinTempSamples.toList()
        }
    }

    private fun clearEcgSamples() {
        synchronized(ecgSamplesLock) {
            ecgSamples.clear()
        }
    }

    private fun snapshotEcgSamples(): List<EcgSample> {
        return synchronized(ecgSamplesLock) {
            ecgSamples.toList()
        }
    }

    private fun <T> trimExpiredSamples(
        samples: ArrayDeque<T>,
        nowMillis: Long,
        timestampOf: (T) -> Long
    ) {
        while (samples.isNotEmpty() && nowMillis - timestampOf(samples.first()) > sampleBufferRetentionMillis) {
            samples.removeFirst()
        }
    }

    private fun uploadTemperatureValue(skinTempValue: SkinTempValue) {
        if (!isValidSkinTempValue(skinTempValue)) {
            return
        }
        scope.launch {
            val payload = buildTemperaturePayload(skinTempValue)
            try {
                uploadPayloadNow(payload)
                val uploadedAtMillis = payload.getLong("timestamp")
                _dataState.value = _dataState.value.copy(
                    lastUploadedSnapshot = UploadedSnapshot(
                        uploadedAtMillis = uploadedAtMillis,
                        sensorType = "TEMP",
                        primaryText = String.format(Locale.getDefault(), "WS %.2f°C", skinTempValue.wristSkinTemperature ?: Float.NaN),
                        secondaryText = skinTempValue.ambientTemperature?.let {
                            String.format(Locale.getDefault(), "AT %.2f°C", it)
                        }
                    )
                )
            } catch (exception: Exception) {
                enqueuePendingUpload(sensorName = "温度", payload = payload)
                handleUploadFailure("温度", exception)
            }
        }
    }

    private fun uploadHeartRateValue(heartRateValue: HeartRateValue) {
        if (!isValidHeartRateValue(heartRateValue)) {
            return
        }
        scope.launch {
            val payload = buildHeartRatePayload(heartRateValue)
            try {
                uploadPayloadNow(payload)
                val uploadedAtMillis = payload.getLong("timestamp")
                _dataState.value = _dataState.value.copy(
                    lastUploadedSnapshot = UploadedSnapshot(
                        uploadedAtMillis = uploadedAtMillis,
                        sensorType = "HR",
                        primaryText = "${heartRateValue.heartRate ?: 0} bpm",
                        secondaryText = "S${heartRateValue.status ?: 0}"
                    )
                )
            } catch (exception: Exception) {
                enqueuePendingUpload(sensorName = "心率", payload = payload)
                handleUploadFailure("心率", exception)
            }
        }
    }

    private suspend fun processValidEdaSamples(samples: List<EDAValue>) {
        if (samples.isEmpty()) {
            return
        }

        edaUploadMutex.withLock {
            val latestSample = samples.last()
            val now = System.currentTimeMillis()
            val startedAtMillis = edaAcquisitionStartedAtMillis ?: now
            val elapsedMillis = (now - startedAtMillis).coerceAtLeast(0L)
            val edaLabel = deriveEdaLabel(latestSample)

            _dataState.value = _dataState.value.copy(
                edaValue = latestSample,
                edaLabel = edaLabel,
                edaValidSampleCount = samples.size,
                lastEdaUpdateAtMillis = now
            )
            recordAcquisition(sensor = "EDA", elapsedMillis = elapsedMillis)
            val payloads = samples.map { buildEdaPayload(it, edaLabel) }
            var nextPendingIndex = 0

            try {
                var uploadedAtMillis = now
                payloads.forEachIndexed { index, payload ->
                    uploadPayloadNow(payload)
                    uploadedAtMillis = payload.getLong("timestamp")
                    nextPendingIndex = index + 1
                }
                _dataState.value = _dataState.value.copy(
                    lastUploadedSnapshot = UploadedSnapshot(
                        uploadedAtMillis = uploadedAtMillis,
                        sensorType = "EDA",
                        primaryText = String.format(Locale.getDefault(), "%.3f", latestSample.skinConductance ?: 0f),
                        secondaryText = if (samples.size == 1) "instant" else "${samples.size} samples"
                    )
                )
                _messageState.emit(
                    ContinuousTrackingMessageState.Info(
                        if (samples.size == 1) {
                            "EDA获取完成，用时 ${formatElapsedDuration(elapsedMillis)}，已发送合法数据"
                        } else {
                            "EDA补发 ${samples.size} 条合法数据，用时 ${formatElapsedDuration(elapsedMillis)}"
                        }
                    )
                )
            } catch (exception: Exception) {
                enqueuePendingUploads(sensorName = "EDA", payloads = payloads.drop(nextPendingIndex))
                handleUploadFailure("EDA", exception)
            }
        }
    }

    private fun buildStableHeartRateValue(samples: List<HeartRateValue>): HeartRateValue? {
        val validSamples = samples.filter {
            isValidHeartRateValue(it) && (it.heartRate ?: 0) in heartRatePlausibleMinBpm..heartRatePlausibleMaxBpm
        }
        if (validSamples.isEmpty()) {
            return null
        }

        val medianHeartRate = medianIntOrNull(validSamples.mapNotNull { it.heartRate }) ?: return null
        val representativeSample = validSamples.minByOrNull { sample ->
            abs((sample.heartRate ?: medianHeartRate) - medianHeartRate)
        } ?: validSamples.last()

        return HeartRateValue(
            heartRate = medianHeartRate,
            status = representativeSample.status,
            timestamp = representativeSample.timestamp
        )
    }

    private fun medianIntOrNull(values: List<Int>): Int? {
        if (values.isEmpty()) {
            return null
        }

        val sortedValues = values.sorted()
        val middleIndex = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            (sortedValues[middleIndex] + sortedValues[middleIndex - 1]) / 2
        } else {
            sortedValues[middleIndex]
        }
    }

    private fun reserveNewEdaSamples(samples: List<EDAValue>): List<EDAValue> {
        if (samples.isEmpty()) {
            return emptyList()
        }

        val orderedSamples = samples
            .sortedBy { it.timestamp ?: Long.MAX_VALUE }
            .distinctBy { it.timestamp ?: Long.MIN_VALUE }

        val lastQueuedTimestampSnapshot = lastQueuedEdaTimestamp
        val newSamples = orderedSamples.filter { sample ->
            val timestamp = sample.timestamp
            timestamp == null || lastQueuedTimestampSnapshot == null || timestamp > lastQueuedTimestampSnapshot
        }

        newSamples.lastOrNull()?.timestamp?.let { lastQueuedEdaTimestamp = it }
        return newSamples
    }

    private fun reserveNewHeartRateSamples(samples: List<HeartRateValue>): List<HeartRateValue> {
        if (samples.isEmpty()) {
            return emptyList()
        }

        val orderedSamples = samples
            .sortedBy { it.timestamp ?: Long.MAX_VALUE }
            .distinctBy { it.timestamp ?: Long.MIN_VALUE }

        val lastQueuedTimestampSnapshot = lastQueuedHeartRateTimestamp
        val newSamples = orderedSamples.filter { sample ->
            val timestamp = sample.timestamp
            timestamp == null || lastQueuedTimestampSnapshot == null || timestamp > lastQueuedTimestampSnapshot
        }

        newSamples.lastOrNull()?.timestamp?.let { lastQueuedHeartRateTimestamp = it }
        return newSamples
    }

    private fun buildTemperaturePayload(skinTempValue: SkinTempValue): JSONObject {
        val uploadedAtMillis = System.currentTimeMillis()
        return JSONObject().apply {
            put("timestamp", uploadedAtMillis)
            put("sensorType", "temperature")
            put("temperature", JSONObject().apply {
                put("wristSkinTemperature", skinTempValue.wristSkinTemperature)
                put("ambientTemperature", skinTempValue.ambientTemperature)
                put("status", skinTempValue.status.name)
            })
        }
    }

    private fun buildHeartRatePayload(heartRateValue: HeartRateValue): JSONObject {
        val uploadedAtMillis = System.currentTimeMillis()
        return JSONObject().apply {
            put("timestamp", uploadedAtMillis)
            put("sensorType", "heart_rate")
            put("heartRate", JSONObject().apply {
                put("bpm", heartRateValue.heartRate)
                put("status", heartRateValue.status)
                put("sampleTimestamp", heartRateValue.timestamp)
            })
        }
    }

    private fun deriveEdaLabel(edaValue: EDAValue): EdaWindowLabel {
        return when (edaValue.status) {
            EDAStatus.DETACHED -> EdaWindowLabel.DETACHED
            EDAStatus.LOW_SIGNAL -> EdaWindowLabel.LOW_SIGNAL
            else -> EdaWindowLabel.STABLE
        }
    }

    private fun buildEdaPayload(edaValue: EDAValue, edaLabel: EdaWindowLabel): JSONObject {
        val uploadedAtMillis = System.currentTimeMillis()
        return JSONObject().apply {
            put("timestamp", uploadedAtMillis)
            put("sensorType", "eda")
            put("eda", JSONObject().apply {
                put("label", edaLabel.name)
                put("validSampleCount", 1)
                put("skinConductance", edaValue.skinConductance)
                put("sampleTimestamp", edaValue.timestamp)
            })
        }
    }

    private fun buildWearStatusPayload(
        isWorn: Boolean,
        changedAtMillis: Long
    ): JSONObject {
        val powerSnapshot = _dataState.value.powerStatusSnapshot
        return JSONObject().apply {
            put("timestamp", changedAtMillis)
            put("event", "wear_state")
            put("isWorn", isWorn)
            put("state", if (isWorn) "WORN" else "UNWORN")
            powerSnapshot?.let { power ->
                put("isCharging", power.isCharging)
                put("chargeSource", power.chargeSource)
                power.batteryLevelPercent?.let { put("batteryLevelPercent", it) }
            }
        }
    }

    private fun buildPowerStatusPayload(
        isCharging: Boolean,
        chargeSource: String,
        batteryLevelPercent: Int?,
        changedAtMillis: Long,
        isWorn: Boolean?
    ): JSONObject {
        return JSONObject().apply {
            put("timestamp", changedAtMillis)
            put("event", "power_state")
            put("isCharging", isCharging)
            put("chargeSource", chargeSource)
            put("state", if (isCharging) "CHARGING" else "ON_BATTERY")
            if (isWorn != null) {
                put("isWorn", isWorn)
            }
            batteryLevelPercent?.let { put("batteryLevelPercent", it) }
        }
    }

    private fun buildEcgPayload(samples: List<EcgSample>): JSONObject {
        val uploadedAtMillis = System.currentTimeMillis()
        return JSONObject().apply {
            put("timestamp", uploadedAtMillis)
            put("sensorType", "ecg")
            put("ecg", JSONObject().apply {
                put("sampleCount", samples.size)
                put("leadOff", false)
                put("samples", JSONArray().apply {
                    samples.forEach { sample ->
                        put(
                            JSONObject().apply {
                                put("timestamp", sample.timestamp)
                                put("mv", sample.valueMv)
                            }
                        )
                    }
                })
            })
        }
    }

    private suspend fun uploadPayloadNow(payload: JSONObject) = withContext(Dispatchers.IO) {
        val connection = (URL(buildUploadEndpoint()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-watch-id", _dataState.value.watchId)
        }

        connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IllegalStateException("Upload failed with code $responseCode")
        }

        connection.disconnect()
    }

    private suspend fun handleUploadFailure(sensorName: String, exception: Exception) {
        val errorDetail = exception.message?.takeIf { it.isNotBlank() } ?: exception.javaClass.simpleName
        Log.w(APP_TAG, "Failed to upload $sensorName payload to ${buildUploadEndpoint()}", exception)
        _messageState.emit(ContinuousTrackingMessageState.Error("上传${sensorName}数据失败，已加入补传队列: $errorDetail"))
    }

    private suspend fun uploadWearStatus(
        isWorn: Boolean,
        changedAtMillis: Long
    ) {
        val payload = buildWearStatusPayload(isWorn = isWorn, changedAtMillis = changedAtMillis)
        try {
            uploadPayloadNow(payload)
        } catch (exception: Exception) {
            enqueuePendingUpload(sensorName = "佩戴状态", payload = payload)
            throw exception
        }
    }

    private suspend fun uploadPowerStatus(
        isCharging: Boolean,
        chargeSource: String,
        batteryLevelPercent: Int?,
        changedAtMillis: Long,
        isWorn: Boolean?
    ) {
        val payload = buildPowerStatusPayload(
            isCharging = isCharging,
            chargeSource = chargeSource,
            batteryLevelPercent = batteryLevelPercent,
            changedAtMillis = changedAtMillis,
            isWorn = isWorn
        )
        try {
            uploadPayloadNow(payload)
        } catch (exception: Exception) {
            enqueuePendingUpload(sensorName = "充电状态", payload = payload)
            throw exception
        }
    }

    private suspend fun enqueuePendingUpload(sensorName: String, payload: JSONObject) {
        enqueuePendingUploads(sensorName = sensorName, payloads = listOf(payload))
    }

    private suspend fun enqueuePendingUploads(sensorName: String, payloads: List<JSONObject>) {
        if (payloads.isEmpty()) {
            return
        }

        pendingUploadQueueMutex.withLock {
            val queue = loadPendingUploadQueue().toMutableList()
            payloads.forEach { payload ->
                queue += PendingUploadRecord(
                    sensorName = sensorName,
                    payload = payload.toString(),
                    enqueuedAtMillis = System.currentTimeMillis()
                )
            }
            val trimmedQueue = if (queue.size > maxPendingUploadCount) {
                queue.takeLast(maxPendingUploadCount)
            } else {
                queue
            }
            savePendingUploadQueue(trimmedQueue)
            Log.w(APP_TAG, "Queued ${payloads.size} pending upload(s) for $sensorName; queueSize=${trimmedQueue.size}")
        }
    }

    private suspend fun replayPendingUploads(trigger: String) {
        var replayedCount = 0
        pendingUploadQueueMutex.withLock {
            val queue = loadPendingUploadQueue().toMutableList()
            if (queue.isEmpty()) {
                return
            }

            while (queue.isNotEmpty()) {
                val next = queue.first()
                try {
                    uploadPayloadNow(JSONObject(next.payload))
                    queue.removeAt(0)
                    replayedCount += 1
                } catch (exception: Exception) {
                    savePendingUploadQueue(queue)
                    Log.w(APP_TAG, "Replay stopped after $replayedCount item(s) on $trigger", exception)
                    return
                }
            }

            savePendingUploadQueue(queue)
        }

        if (replayedCount > 0) {
            Log.i(APP_TAG, "Replayed $replayedCount pending upload(s) after $trigger")
            _messageState.emit(ContinuousTrackingMessageState.Info("网络恢复，已补传 $replayedCount 条待发送数据"))
        }
    }

    private fun loadPendingUploadQueue(): List<PendingUploadRecord> {
        val rawQueue = preferences.getString(pendingUploadQueueKey, null).orEmpty()
        if (rawQueue.isBlank()) {
            return emptyList()
        }

        return try {
            val jsonArray = JSONArray(rawQueue)
            buildList(jsonArray.length()) {
                for (index in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(index) ?: continue
                    val sensorName = item.optString("sensorName")
                    val payload = item.optString("payload")
                    if (sensorName.isBlank() || payload.isBlank()) {
                        continue
                    }
                    add(
                        PendingUploadRecord(
                            sensorName = sensorName,
                            payload = payload,
                            enqueuedAtMillis = item.optLong("enqueuedAtMillis")
                        )
                    )
                }
            }
        } catch (exception: Exception) {
            Log.w(APP_TAG, "Failed to parse pending upload queue; clearing it", exception)
            preferences.edit().remove(pendingUploadQueueKey).apply()
            emptyList()
        }
    }

    private fun savePendingUploadQueue(queue: List<PendingUploadRecord>) {
        if (queue.isEmpty()) {
            preferences.edit().remove(pendingUploadQueueKey).apply()
            return
        }

        val jsonArray = JSONArray()
        queue.forEach { record ->
            jsonArray.put(
                JSONObject().apply {
                    put("sensorName", record.sensorName)
                    put("payload", record.payload)
                    put("enqueuedAtMillis", record.enqueuedAtMillis)
                }
            )
        }
        preferences.edit().putString(pendingUploadQueueKey, jsonArray.toString()).apply()
    }

    private fun isValidEdaValue(value: EDAValue?): Boolean {
        return value != null && value.status == EDAStatus.NORMAL && (value.skinConductance ?: 0f) > 0f
    }

    private fun isValidSkinTempValue(value: SkinTempValue?): Boolean {
        return value != null &&
            value.status == SkinTempStatus.SUCCESSFUL_MEASUREMENT &&
            isPlausibleWristSkinTemperature(value.wristSkinTemperature) &&
            isPlausibleAmbientTemperature(value.ambientTemperature)
    }

    private fun isPlausibleWristSkinTemperature(value: Float?): Boolean {
        return value != null && value in 15f..50f
    }

    private fun isPlausibleAmbientTemperature(value: Float?): Boolean {
        return value == null || value in -20f..60f
    }

    private fun isValidHeartRateValue(value: HeartRateValue?): Boolean {
        return value != null && (value.heartRate ?: 0) > 0 && (value.status ?: 0) > 0
    }

    private fun extractEdaValues(dataPoint: DataPoint): EDAValue {
        val skinConductance = dataPoint.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
        val edaStatus = dataPoint.getValue(ValueKey.EdaSet.STATUS)
        val edaTimestamp = dataPoint.timestamp

        return EDAValue(skinConductance, EDAStatus.fromInt(edaStatus), edaTimestamp)
    }

    private fun extractSkinTempValues(dataPoint: DataPoint): SkinTempValue {
        val status = dataPoint.getValue(ValueKey.SkinTemperatureSet.STATUS)
        val parsedStatus = SkinTempStatus.fromInt(status)
        val wristSkinTemperature = if (parsedStatus == SkinTempStatus.SUCCESSFUL_MEASUREMENT) {
            dataPoint.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
        } else {
            null
        }
        val ambientTemperature = if (parsedStatus == SkinTempStatus.SUCCESSFUL_MEASUREMENT) {
            dataPoint.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
        } else {
            null
        }

        return SkinTempValue(
            ambientTemperature = ambientTemperature,
            wristSkinTemperature = wristSkinTemperature,
            status = parsedStatus
        )
    }

    private fun extractHeartRateValues(dataPoint: DataPoint): HeartRateValue {
        val heartRate = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE)
        val status = dataPoint.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
        val timestamp = dataPoint.timestamp

        return HeartRateValue(
            heartRate = heartRate,
            status = status,
            timestamp = timestamp
        )
    }

    private fun extractPpgValues(dataPoint: DataPoint): PpgValue {
        val green = dataPoint.getValue(ValueKey.PpgSet.PPG_GREEN)
        val greenStatus = dataPoint.getValue(ValueKey.PpgSet.GREEN_STATUS)
        val red = dataPoint.getValue(ValueKey.PpgSet.PPG_RED)
        val redStatus = dataPoint.getValue(ValueKey.PpgSet.RED_STATUS)
        val ir = dataPoint.getValue(ValueKey.PpgSet.PPG_IR)
        val irStatus = dataPoint.getValue(ValueKey.PpgSet.IR_STATUS)

        return PpgValue(
            green = green,
            greenStatus = greenStatus,
            red = red,
            redStatus = redStatus,
            ir = ir,
            irStatus = irStatus
        )
    }

    private fun loadUploadHost(): String {
        val storedHost = preferences.getString("upload_host", defaultUploadHost)?.trim().orEmpty()
        if (storedHost == legacyUploadHost) {
            preferences.edit().putString("upload_host", defaultUploadHost).apply()
            return defaultUploadHost
        }
        return storedHost.ifEmpty { defaultUploadHost }
    }

    private fun loadUploadPort(): Int {
        val storedPort = preferences.getInt("upload_port", defaultUploadPort)
        if (storedPort == legacyUploadPort || storedPort == 5000) {
            // Clear stale cached port and use current default
            preferences.edit().putInt("upload_port", defaultUploadPort).apply()
            return defaultUploadPort
        }
        return storedPort.coerceIn(1, 65535)
    }

    private fun buildUploadEndpoint(): String {
        val snapshot = _dataState.value
        return "http://${snapshot.uploadHost}:${snapshot.uploadPort}/api/samsung-watch"
    }

    private fun recordAcquisition(sensor: String, elapsedMillis: Long) {
        _dataState.value = _dataState.value.copy(
            lastAcquiredSensor = sensor,
            lastAcquisitionElapsedMillis = elapsedMillis,
            lastAcquisitionAtMillis = System.currentTimeMillis()
        )
    }

    private fun formatElapsedDuration(elapsedMillis: Long): String {
        return String.format(Locale.getDefault(), "%.1fs", elapsedMillis / 1000f)
    }

    private data class TimestampedSkinTempSample(
        val sample: SkinTempValue,
        val receivedAtMillis: Long
    )

    private data class EcgSample(
        val timestamp: Long,
        val valueMv: Float
    )

    private data class PendingUploadRecord(
        val sensorName: String,
        val payload: String,
        val enqueuedAtMillis: Long
    )

}