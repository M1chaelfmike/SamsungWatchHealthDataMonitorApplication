package com.samsung.health.sensorsdksample.edatracking.pairing

import com.samsung.health.sensorsdksample.edatracking.config.WatchConfiguration

enum class WatchPairingReason {
    FIRST_LAUNCH,
    NETWORK_CHANGED,
    WAITING_FOR_CONFIG,
    PAIRED,
    RECEIVER_ERROR
}

data class WatchPairingState(
    val requiresPairing: Boolean = true,
    val canSkip: Boolean = false,
    val receiverRunning: Boolean = false,
    val receiverPort: Int = WatchPairingManager.PAIRING_PORT,
    val watchIp: String? = null,
    val macAddress: String? = null,
    val pairingCode: String = "",
    val configuration: WatchConfiguration,
    val reason: WatchPairingReason = WatchPairingReason.FIRST_LAUNCH,
    val message: String? = null
)
