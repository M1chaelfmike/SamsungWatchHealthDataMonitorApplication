package com.samsung.health.sensorsdksample.edatracking.data

sealed class ContinuousTrackingProgressState {
    data object Idle : ContinuousTrackingProgressState()
    data object Tracking : ContinuousTrackingProgressState()
    data object TrackingDisabled : ContinuousTrackingProgressState()
}