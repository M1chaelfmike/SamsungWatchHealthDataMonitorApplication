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
package com.samsung.health.sensorsdksample.edatracking

import com.samsung.health.sensorsdksample.edatracking.config.WatchConfiguration
import com.samsung.health.sensorsdksample.edatracking.config.WatchEndpoints
import com.samsung.health.sensorsdksample.edatracking.data.EDAStatus
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodelabTests {

    @Test
    fun watchUploadEndpoint_isBuiltFromHostAndPort() {
        val endpoint = WatchEndpoints.samsungWatchUpload(
            host = "192.168.0.5",
            port = 3100
        )

        assertEquals("http://192.168.0.5:3100/api/samsung-watch", endpoint)
    }

    @Test
    fun pairingHandshakeEndpoint_isBuiltFromHostAndPort() {
        val endpoint = WatchEndpoints.pairingHandshake(
            host = "192.168.0.5",
            port = 3100
        )

        assertEquals("http://192.168.0.5:3100/api/watch-pairing/handshake", endpoint)
    }

    @Test
    fun watchConfiguration_canReuseStoredTargetBeforePairing() {
        val configuration = WatchConfiguration(
            watchId = "real-watch-001",
            serverHost = "192.168.0.5",
            serverPort = 3100,
            paired = false,
            hasStoredTarget = true
        )

        assertTrue(configuration.canUseExistingTarget)
    }

    @Test
    fun watchConfiguration_requiresPairingWithoutStoredTarget() {
        val configuration = WatchConfiguration(
            watchId = "real-watch-001",
            serverHost = "192.168.0.5",
            serverPort = 3100,
            paired = false,
            hasStoredTarget = false
        )

        assertFalse(configuration.canUseExistingTarget)
    }

    @Test
    fun sensorStatusConverters_returnUnknownForUnexpectedValues() {
        assertEquals(EDAStatus.UNKNOWN, EDAStatus.fromInt(999))
        assertEquals(SkinTempStatus.UNKNOWN, SkinTempStatus.fromInt(999))
    }
}
