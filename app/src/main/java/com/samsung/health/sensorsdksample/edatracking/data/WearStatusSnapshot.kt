package com.samsung.health.sensorsdksample.edatracking.data

data class WearStatusSnapshot(
    val isWorn: Boolean,
    val isCharging: Boolean? = null,
    val chargeSource: String? = null,
    val batteryLevelPercent: Int? = null,
    val changedAtMillis: Long
)