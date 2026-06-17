package com.samsung.health.sensorsdksample.edatracking.config

/**
 * Persisted runtime configuration used by the watch when it uploads sensor data.
 */
data class WatchConfiguration(
    val watchId: String,
    val serverHost: String,
    val serverPort: Int,
    val paired: Boolean,
    val hasStoredTarget: Boolean
) {
    val canUseExistingTarget: Boolean
        get() = paired || hasStoredTarget
}

/**
 * Configuration payload received from the backend pairing flow.
 */
data class RemoteWatchConfiguration(
    val watchId: String?,
    val serverHost: String,
    val serverPort: Int
)
