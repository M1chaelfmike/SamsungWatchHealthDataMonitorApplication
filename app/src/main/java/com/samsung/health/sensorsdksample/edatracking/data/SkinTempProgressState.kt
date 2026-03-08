package com.samsung.health.sensorsdksample.edatracking.data

sealed class SkinTempProgressState {
    data object Idle : SkinTempProgressState()
    data object Measuring : SkinTempProgressState()
    data object TrackingDisabled : SkinTempProgressState()
}