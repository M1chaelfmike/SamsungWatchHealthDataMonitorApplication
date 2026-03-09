package com.samsung.health.sensorsdksample.edatracking.data

data class ContinuousMonitoringData(
    val edaValue: EDAValue? = null,
    val edaLabel: EdaWindowLabel? = null,
    val edaValidSampleCount: Int = 0,
    val lastEdaUpdateAtMillis: Long? = null,
    val skinTempValue: SkinTempValue? = null,
    val lastSkinTempUpdateAtMillis: Long? = null,
    val heartRateValue: HeartRateValue? = null,
    val lastHeartRateUpdateAtMillis: Long? = null,
    val liveHeartRateValue: HeartRateValue? = null,
    val lastAcquiredSensor: String? = null,
    val lastAcquisitionElapsedMillis: Long? = null,
    val lastAcquisitionAtMillis: Long? = null,
    val lastUploadedSnapshot: UploadedSnapshot? = null,
    val wearStatusSnapshot: WearStatusSnapshot? = null,
    val uploadHost: String = "192.168.0.8",
    val uploadPort: Int = 8080,
    val ppgValue: PpgValue? = null
)