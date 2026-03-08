package com.samsung.health.sensorsdksample.edatracking.data

data class SkinTempValue(
    val ambientTemperature: Float?,
    val wristSkinTemperature: Float?,
    val status: SkinTempStatus
)