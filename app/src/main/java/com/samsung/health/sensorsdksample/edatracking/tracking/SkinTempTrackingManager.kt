package com.samsung.health.sensorsdksample.edatracking.tracking

import android.content.Context
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempDataState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempMessageState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempProgressState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempValue
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
class SkinTempTrackingManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val sharedServiceManager: SharedHealthTrackingServiceManager
) {

    private var skinTempTracker: HealthTracker? = null
    private var hasConnectionLease = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _dataState = MutableStateFlow<SkinTempDataState>(SkinTempDataState.Initial)
    val dataState = _dataState.asStateFlow()

    private val _progressState = MutableStateFlow<SkinTempProgressState>(SkinTempProgressState.Idle)
    val progressState = _progressState.asStateFlow()

    private val _messageState = MutableSharedFlow<SkinTempMessageState>()
    val messageState = _messageState.asSharedFlow()

    private val _connectionState = MutableStateFlow<SkinTempConnectionState>(SkinTempConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    init {
        scope.launch {
            sharedServiceManager.isConnected.collect { isConnected ->
                _connectionState.value = if (isConnected) {
                    checkTrackerAvailability()
                    SkinTempConnectionState.Connected
                } else {
                    SkinTempConnectionState.Disconnected
                }
            }
        }

        scope.launch {
            sharedServiceManager.connectionErrors.collect { exception ->
                if (exception.hasResolution()) {
                    _messageState.emit(SkinTempMessageState.ResolvableError(exception))
                } else {
                    _messageState.emit(SkinTempMessageState.Error(exception.message))
                }
            }
        }
    }

    private val trackerListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            data.lastOrNull()?.let { dataPoint ->
                _dataState.value = SkinTempDataState.DataObtained(extractSkinTempValues(dataPoint))
                stopTracking()
            }
        }

        override fun onFlushCompleted() = Unit

        override fun onError(error: HealthTracker.TrackerError) {
            if (_progressState.value == SkinTempProgressState.Measuring) {
                _progressState.value = SkinTempProgressState.Idle
            }
            scope.launch {
                when (error) {
                    HealthTracker.TrackerError.PERMISSION_ERROR -> _messageState.emit(SkinTempMessageState.PermissionError)
                    HealthTracker.TrackerError.SDK_POLICY_ERROR -> _messageState.emit(SkinTempMessageState.SdkPolicyError)
                    else -> _messageState.emit(SkinTempMessageState.Error(error.name))
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
                _messageState.emit(SkinTempMessageState.Error(exception.message))
            }
        }
    }

    fun disconnect() {
        skinTempTracker?.unsetEventListener()
        skinTempTracker = null
        if (hasConnectionLease) {
            sharedServiceManager.releaseConnection()
            hasConnectionLease = false
        }
        if (_progressState.value != SkinTempProgressState.TrackingDisabled) {
            _progressState.value = SkinTempProgressState.Idle
        }
    }

    fun startTracking() {
        skinTempTracker = sharedServiceManager.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)

        if (skinTempTracker != null) {
            _progressState.value = SkinTempProgressState.Measuring
            skinTempTracker?.setEventListener(trackerListener)
        } else {
            scope.launch {
                _messageState.emit(SkinTempMessageState.Error("Skin temperature tracker is not initialized"))
            }
        }
    }

    fun stopTracking() {
        skinTempTracker?.unsetEventListener()
        if (_progressState.value == SkinTempProgressState.Measuring) {
            _progressState.value = SkinTempProgressState.Idle
        }
    }

    private fun checkTrackerAvailability() {
        val availableTrackers = sharedServiceManager.getSupportedTrackers()

        if (availableTrackers == null || !availableTrackers.contains(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)) {
            _progressState.value = SkinTempProgressState.TrackingDisabled
            scope.launch {
                _messageState.emit(SkinTempMessageState.TrackerNotAvailable)
            }
        } else if (_progressState.value == SkinTempProgressState.TrackingDisabled) {
            _progressState.value = SkinTempProgressState.Idle
        }
    }

    fun extractSkinTempValues(dataPoint: DataPoint): SkinTempValue {
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
}