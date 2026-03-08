/*
 * Copyright 2025 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.samsung.health.sensorsdksample.edatracking.tracking

import android.content.Context
import android.util.Log
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTracker.TrackerEventListener
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.health.sensorsdksample.edatracking.data.ConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.DataState
import com.samsung.health.sensorsdksample.edatracking.data.EDAStatus
import com.samsung.health.sensorsdksample.edatracking.data.EDAValue
import com.samsung.health.sensorsdksample.edatracking.data.MessageState
import com.samsung.health.sensorsdksample.edatracking.data.ProgressState
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
class EDATrackingManager @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val sharedServiceManager: SharedHealthTrackingServiceManager,
    private val trackerSessionCoordinator: TrackerSessionCoordinator
) {

    private var edaTracker: HealthTracker? = null
    private var hasConnectionLease = false
    private var hasTrackingOwnership = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _dataState = MutableStateFlow<DataState>(DataState.Initial)
    val dataState = _dataState.asStateFlow()

    private val _progressState = MutableStateFlow<ProgressState>(ProgressState.Measuring(false))
    val progressState = _progressState.asStateFlow()

    private val _messageState = MutableSharedFlow<MessageState>()
    val messageState = _messageState.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    init {
        scope.launch {
            sharedServiceManager.isConnected.collect { isConnected ->
                _connectionState.value = if (isConnected) {
                    checkTrackerAvailability()
                    ConnectionState.Connected
                } else {
                    ConnectionState.Disconnected
                }
            }
        }

        scope.launch {
            sharedServiceManager.connectionErrors.collect { exception ->
                if (exception.hasResolution()) {
                    _messageState.emit(MessageState.ResolvableError(exception))
                } else {
                    exception.printStackTrace()
                    _messageState.emit(MessageState.Error(exception.message))
                }
            }
        }
    }

    private val trackerListener = object : TrackerEventListener {
        override fun onDataReceived(data: MutableList<DataPoint>) {
            data.forEach { dataPoint ->
                _dataState.value = DataState.DataObtained(extractEdaValues(dataPoint))
            }
        }

        override fun onFlushCompleted() {
            Log.i(APP_TAG, "Data flushed")
        }

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(APP_TAG, error.name)
            if (error == HealthTracker.TrackerError.PERMISSION_ERROR) {
                scope.launch {
                    _messageState.emit(MessageState.PermissionError)
                }
            } else {
                scope.launch {
                    _messageState.emit(MessageState.Error(error.name))
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
                _messageState.emit(MessageState.Error(exception.message))
            }
        }
    }

    fun disconnect() {
        stopTracking()
        if (hasConnectionLease) {
            sharedServiceManager.releaseConnection()
            hasConnectionLease = false
        }
    }

    fun startTracking() {
        if (!sharedServiceManager.isConnected.value) {
            scope.launch {
                _messageState.emit(MessageState.Error("Health Tracking Service is not connected"))
            }
            return
        }

        if (!trackerSessionCoordinator.tryAcquireEda(TrackerOwner.PAGE1_EDA)) {
            scope.launch {
                _messageState.emit(MessageState.TrackingInUse)
            }
            return
        }
        hasTrackingOwnership = true
        edaTracker = obtainEDATracker()

        if (edaTracker != null) {
            try {
                edaTracker!!.setEventListener(trackerListener)
                _progressState.value = ProgressState.Measuring(true)
            } catch (exception: IllegalStateException) {
                trackerSessionCoordinator.releaseEda(TrackerOwner.PAGE1_EDA)
                hasTrackingOwnership = false
                edaTracker = null
                scope.launch {
                    _messageState.emit(MessageState.Error("Health Tracking Service is not ready yet"))
                }
            }
        } else {
            trackerSessionCoordinator.releaseEda(TrackerOwner.PAGE1_EDA)
            hasTrackingOwnership = false
            scope.launch {
                _messageState.emit(MessageState.TrackerNotInitialized)
            }
        }
    }

    fun stopTracking() {
        if (edaTracker != null) {
            edaTracker!!.unsetEventListener()
        }
        edaTracker = null
        _progressState.value = ProgressState.Measuring(false)
        if (hasTrackingOwnership) {
            trackerSessionCoordinator.releaseEda(TrackerOwner.PAGE1_EDA)
            hasTrackingOwnership = false
        }
    }

    private fun checkTrackerAvailability() {
        val availableTrackers = sharedServiceManager.getSupportedTrackers()

        if (availableTrackers == null || !availableTrackers.contains(HealthTrackerType.EDA_CONTINUOUS)) {
            _progressState.value = ProgressState.TrackingDisabled
            scope.launch {
                _messageState.emit(MessageState.TrackerNotAvailable)
            }
        }
    }

    fun obtainEDATracker(): HealthTracker? {
        var edaTracker: HealthTracker? = null

        edaTracker = sharedServiceManager.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)

        return edaTracker
    }

    fun extractEdaValues(dataPoint: DataPoint): EDAValue {
        var skinConductance: Float? = null
        var edaStatus: Int? = null
        var edaTimestamp: Long? = null

        skinConductance = dataPoint.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)
        edaStatus = dataPoint.getValue(ValueKey.EdaSet.STATUS)
        edaTimestamp = dataPoint.timestamp

        return EDAValue(skinConductance, EDAStatus.fromInt(edaStatus), edaTimestamp)
    }
}
