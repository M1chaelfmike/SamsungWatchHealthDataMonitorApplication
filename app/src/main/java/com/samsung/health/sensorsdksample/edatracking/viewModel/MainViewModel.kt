/*
 * Copyright 2025 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.samsung.health.sensorsdksample.edatracking.viewModel

import androidx.lifecycle.ViewModel
import com.samsung.health.sensorsdksample.edatracking.data.ConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.DataState
import com.samsung.health.sensorsdksample.edatracking.data.ProgressState
import com.samsung.health.sensorsdksample.edatracking.tracking.EDATrackingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val trackingManager: EDATrackingManager
) : ViewModel() {

    val dataState: StateFlow<DataState> = trackingManager.dataState
    val progressState: StateFlow<ProgressState> = trackingManager.progressState
    val connectionState: StateFlow<ConnectionState> = trackingManager.connectionState
    val messageState = trackingManager.messageState

    fun connect() {
        trackingManager.connect()
    }

    fun disconnect() {
        trackingManager.disconnect()
    }

    fun startTracking() {
        trackingManager.startTracking()
    }

    fun stopTracking() {
        trackingManager.stopTracking()
    }
}
