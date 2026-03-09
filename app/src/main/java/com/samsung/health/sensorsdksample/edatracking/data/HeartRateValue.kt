package com.samsung.health.sensorsdksample.edatracking.data

data class HeartRateValue(
    val heartRate: Int?,
    val status: Int?,
    val timestamp: Long? = null
)