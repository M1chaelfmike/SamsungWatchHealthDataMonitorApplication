package com.samsung.health.sensorsdksample.edatracking.data

data class PowerStatusSnapshot(
    val isCharging: Boolean,
    val chargeSource: String,
    val batteryLevelPercent: Int?,
    val changedAtMillis: Long
)
