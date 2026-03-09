package com.samsung.health.sensorsdksample.edatracking.data

import com.samsung.android.service.health.tracking.HealthTrackerException

sealed class ContinuousTrackingMessageState {
    data class UnsupportedSensors(
        val edaSupported: Boolean,
        val skinTemperatureSupported: Boolean,
        val heartRateSupported: Boolean,
        val ppgSupported: Boolean
    ) : ContinuousTrackingMessageState()

    data object PermissionError : ContinuousTrackingMessageState()
    data class ResolvableError(val exception: HealthTrackerException) : ContinuousTrackingMessageState()
    data class Error(val errorMessage: String?) : ContinuousTrackingMessageState()
    data class Info(val message: String) : ContinuousTrackingMessageState()
    data object TrackingInUse : ContinuousTrackingMessageState()
}