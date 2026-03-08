package com.samsung.health.sensorsdksample.edatracking.data

import com.samsung.android.service.health.tracking.HealthTrackerException

sealed class SkinTempMessageState {
    data object TrackerNotAvailable : SkinTempMessageState()
    data object PermissionError : SkinTempMessageState()
    data object SdkPolicyError : SkinTempMessageState()
    data class Error(val errorMessage: String?) : SkinTempMessageState()
    data class ResolvableError(val exception: HealthTrackerException) : SkinTempMessageState()
}