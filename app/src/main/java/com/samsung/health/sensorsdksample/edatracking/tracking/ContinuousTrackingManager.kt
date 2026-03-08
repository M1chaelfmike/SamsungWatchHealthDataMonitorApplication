package com.samsung.health.sensorsdksample.edatracking.tracking

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousMonitoringData
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState
import com.samsung.health.sensorsdksample.edatracking.data.EDAStatus
import com.samsung.health.sensorsdksample.edatracking.data.EDAValue
import com.samsung.health.sensorsdksample.edatracking.data.HeartRateValue
import com.samsung.health.sensorsdksample.edatracking.data.PpgValue
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempValue
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity.Companion.APP_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContinuousTrackingManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val sharedServiceManager: SharedHealthTrackingServiceManager,
    private val trackerSessionCoordinator: TrackerSessionCoordinator
) {

    private val requestedPpgTypes = setOf(PpgType.GREEN, PpgType.IR, PpgType.RED)

    private var edaTracker: HealthTracker? = null
    private var skinTempTracker: HealthTracker? = null
    private var heartRateTracker: HealthTracker? = null
    private var ppgTracker: HealthTracker? = null
    private var hasConnectionLease = false
    private var hasTrackingOwnership = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _dataState = MutableStateFlow(ContinuousMonitoringData())
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
            data.lastOrNull()?.let { dataPoint ->
                _dataState.value = _dataState.value.copy(edaValue = extractEdaValues(dataPoint))
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "EDA data flushed")
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

    private val skinTempTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            data.lastOrNull()?.let { dataPoint ->
                _dataState.value = _dataState.value.copy(skinTempValue = extractSkinTempValues(dataPoint))
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "Skin temperature data flushed")
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

    private val heartRateTrackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            data.lastOrNull()?.let { dataPoint ->
                _dataState.value = _dataState.value.copy(heartRateValue = extractHeartRateValues(dataPoint))
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "Heart rate data flushed")
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
        if (!sharedServiceManager.isConnected.value) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Health Tracking Service is not connected"))
            }
            return
        }

        val availableTrackers = sharedServiceManager.getSupportedTrackers().orEmpty()
        val edaSupported = availableTrackers.contains(HealthTrackerType.EDA_CONTINUOUS)
        val skinTempSupported = availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
        val heartRateSupported = availableTrackers.contains(HealthTrackerType.HEART_RATE_CONTINUOUS)
        val ppgSupported = availableTrackers.contains(HealthTrackerType.PPG_CONTINUOUS)

        if (!edaSupported && !skinTempSupported && !heartRateSupported && !ppgSupported) {
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

        if (edaSupported && !trackerSessionCoordinator.tryAcquireEda(TrackerOwner.PAGE3_CONTINUOUS)) {
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.TrackingInUse)
            }
            return
        }
        hasTrackingOwnership = edaSupported

        edaTracker = if (edaSupported) sharedServiceManager.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS) else null
        skinTempTracker = if (skinTempSupported) {
            sharedServiceManager.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS)
        } else {
            null
        }
        heartRateTracker = if (heartRateSupported) {
            sharedServiceManager.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
        } else {
            null
        }
        ppgTracker = if (ppgSupported) {
            sharedServiceManager.getPpgHealthTracker(HealthTrackerType.PPG_CONTINUOUS, requestedPpgTypes)
        } else {
            null
        }

        var trackerStarted = false

        try {
            edaTracker?.let {
                it.setEventListener(edaTrackerListener)
                trackerStarted = true
            }
            skinTempTracker?.let {
                it.setEventListener(skinTempTrackerListener)
                trackerStarted = true
            }
            heartRateTracker?.let {
                it.setEventListener(heartRateTrackerListener)
                trackerStarted = true
            }
            ppgTracker?.let {
                it.setEventListener(ppgTrackerListener)
                trackerStarted = true
            }
        } catch (exception: IllegalStateException) {
            stopTracking()
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Health Tracking Service is not ready yet"))
            }
            return
        }

        if (!trackerStarted) {
            if (hasTrackingOwnership) {
                trackerSessionCoordinator.releaseEda(TrackerOwner.PAGE3_CONTINUOUS)
                hasTrackingOwnership = false
            }
            scope.launch {
                _messageState.emit(ContinuousTrackingMessageState.Error("Continuous trackers are not initialized"))
            }
            return
        }

        _progressState.value = ContinuousTrackingProgressState.Tracking
    }

    fun stopTracking() {
        edaTracker?.unsetEventListener()
        skinTempTracker?.unsetEventListener()
        heartRateTracker?.unsetEventListener()
        ppgTracker?.unsetEventListener()
        edaTracker = null
        skinTempTracker = null
        heartRateTracker = null
        ppgTracker = null
        if (_progressState.value == ContinuousTrackingProgressState.Tracking) {
            _progressState.value = ContinuousTrackingProgressState.Idle
        }
        if (hasTrackingOwnership) {
            trackerSessionCoordinator.releaseEda(TrackerOwner.PAGE3_CONTINUOUS)
            hasTrackingOwnership = false
        }
    }

    private fun checkTrackerAvailability() {
        val availableTrackers = sharedServiceManager.getSupportedTrackers()
        val edaSupported = availableTrackers?.contains(HealthTrackerType.EDA_CONTINUOUS) == true
        val skinTempSupported = availableTrackers?.contains(HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS) == true
        val heartRateSupported = availableTrackers?.contains(HealthTrackerType.HEART_RATE_CONTINUOUS) == true
        val ppgSupported = availableTrackers?.contains(HealthTrackerType.PPG_CONTINUOUS) == true

        if (!edaSupported && !skinTempSupported && !heartRateSupported && !ppgSupported) {
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

        return HeartRateValue(
            heartRate = heartRate,
            status = status
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
}