package com.samsung.health.sensorsdksample.edatracking.viewModel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousMonitoringData
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState
import com.samsung.health.sensorsdksample.edatracking.tracking.ContinuousTrackingForegroundService
import com.samsung.health.sensorsdksample.edatracking.tracking.ContinuousTrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ContinuousTrackingViewModel @Inject constructor(
    private val trackingManager: ContinuousTrackingManager
) : ViewModel() {

    val dataState: StateFlow<ContinuousMonitoringData> = trackingManager.dataState
    val progressState: StateFlow<ContinuousTrackingProgressState> = trackingManager.progressState
    val connectionState: StateFlow<ContinuousConnectionState> = trackingManager.connectionState
    val messageState = trackingManager.messageState

    fun connect() {
        trackingManager.connect()
    }

    fun disconnect() {
        trackingManager.disconnect()
    }

    fun startTracking() {
        trackingManager.startTracking()
    }

    fun startBackgroundTracking(context: Context) {
        ContinuousTrackingForegroundService.start(context)
    }

    fun stopBackgroundTracking(context: Context) {
        ContinuousTrackingForegroundService.stop(context)
    }

    fun updateUploadTarget(host: String, port: Int) {
        trackingManager.updateUploadTarget(host, port)
    }

    fun stopTracking() {
        trackingManager.stopTracking()
    }
}