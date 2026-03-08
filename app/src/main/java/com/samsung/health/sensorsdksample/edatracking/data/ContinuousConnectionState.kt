package com.samsung.health.sensorsdksample.edatracking.data

sealed class ContinuousConnectionState {
    data object Connected : ContinuousConnectionState()
    data object Disconnected : ContinuousConnectionState()
}