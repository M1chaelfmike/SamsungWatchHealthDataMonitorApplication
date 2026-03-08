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
package com.samsung.health.sensorsdksample.edatracking.data

import com.samsung.android.service.health.tracking.HealthTrackerException

sealed class MessageState {
    data object TrackerNotAvailable : MessageState()
    data object PermissionError : MessageState()
    class Error(val errorMessage: String?) : MessageState()
    class ResolvableError(val exception: HealthTrackerException) : MessageState()
    data object TrackerNotInitialized : MessageState()
    data object TrackingInUse : MessageState()
}
