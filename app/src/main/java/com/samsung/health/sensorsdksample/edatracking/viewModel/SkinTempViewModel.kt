package com.samsung.health.sensorsdksample.edatracking.viewModel

import androidx.lifecycle.ViewModel
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempDataState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempProgressState
import com.samsung.health.sensorsdksample.edatracking.tracking.SkinTempTrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SkinTempViewModel @Inject constructor(
    private val trackingManager: SkinTempTrackingManager
) : ViewModel() {

    val dataState: StateFlow<SkinTempDataState> = trackingManager.dataState
    val progressState: StateFlow<SkinTempProgressState> = trackingManager.progressState
    val connectionState: StateFlow<SkinTempConnectionState> = trackingManager.connectionState
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

    fun stopTracking() {
        trackingManager.stopTracking()
    }
}