package com.samsung.health.sensorsdksample.edatracking.data

enum class SkinTempStatus(val value: Int) {
    SUCCESSFUL_MEASUREMENT(0),
    INVALID_MEASUREMENT(-1),
    UNKNOWN(Int.MAX_VALUE);

    companion object {
        fun fromInt(value: Int?): SkinTempStatus {
            return entries.find { it.value == value } ?: UNKNOWN
        }
    }
}