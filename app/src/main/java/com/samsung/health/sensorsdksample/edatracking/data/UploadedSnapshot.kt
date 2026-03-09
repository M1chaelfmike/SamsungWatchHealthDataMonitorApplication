package com.samsung.health.sensorsdksample.edatracking.data

data class UploadedSnapshot(
    val uploadedAtMillis: Long,
    val sensorType: String,
    val primaryText: String,
    val secondaryText: String? = null
)