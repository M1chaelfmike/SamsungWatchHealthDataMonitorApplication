package com.samsung.health.sensorsdksample.edatracking.data

data class ContinuousMonitoringData(
    val edaValue: EDAValue? = null,
    val skinTempValue: SkinTempValue? = null,
    val heartRateValue: HeartRateValue? = null,
    val ppgValue: PpgValue? = null
)