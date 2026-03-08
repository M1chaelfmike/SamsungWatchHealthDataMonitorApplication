package com.samsung.health.sensorsdksample.edatracking.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class TrackerOwner {
    NONE,
    PAGE1_EDA,
    PAGE3_CONTINUOUS,
}

@Singleton
class TrackerSessionCoordinator @Inject constructor() {

    private val _edaOwner = MutableStateFlow(TrackerOwner.NONE)
    val edaOwner = _edaOwner.asStateFlow()

    @Synchronized
    fun tryAcquireEda(owner: TrackerOwner): Boolean {
        if (_edaOwner.value == TrackerOwner.NONE || _edaOwner.value == owner) {
            _edaOwner.value = owner
            return true
        }
        return false
    }

    @Synchronized
    fun releaseEda(owner: TrackerOwner) {
        if (_edaOwner.value == owner) {
            _edaOwner.value = TrackerOwner.NONE
        }
    }
}