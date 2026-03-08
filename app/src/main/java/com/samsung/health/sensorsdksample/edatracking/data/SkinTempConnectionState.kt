package com.samsung.health.sensorsdksample.edatracking.data

sealed class SkinTempConnectionState {
    data object Connected : SkinTempConnectionState()
    data object Disconnected : SkinTempConnectionState()
}