package com.samsung.health.sensorsdksample.edatracking.data

sealed class SkinTempDataState {
    data object Initial : SkinTempDataState()
    data class DataObtained(val value: SkinTempValue) : SkinTempDataState()
}