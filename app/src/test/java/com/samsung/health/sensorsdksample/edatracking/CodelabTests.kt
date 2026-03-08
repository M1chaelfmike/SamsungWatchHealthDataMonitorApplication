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

import android.content.Context
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.Value
import com.samsung.android.service.health.tracking.data.ValueKey
import com.samsung.health.sensorsdksample.edatracking.presentation.MainActivity
import com.samsung.health.sensorsdksample.edatracking.tracking.EDATrackingManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CodelabTests {
    private lateinit var activity: MainActivity
    private lateinit var trackingManager: EDATrackingManager
    private lateinit var context: Context

    @Before
    fun setUp() {
        activity = MainActivity()
        context = RuntimeEnvironment.getApplication()
        trackingManager = EDATrackingManager(context)
    }

    @Test
    fun testPreparePermission_NonNull() {
        val permission = activity.preparePermission()

        Assert.assertNotNull(permission)
    }

    @Test
    fun testObtainHealthTracker_NotNull() {
        val mockHealthTrackingService = mockk<HealthTrackingService>()
        val mockEdaTracker = mockk<HealthTracker>()
        every { mockHealthTrackingService.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS) } returns mockEdaTracker
        
        val healthTrackingServiceField = EDATrackingManager::class.java.getDeclaredField("healthTrackingService")
        healthTrackingServiceField.isAccessible = true
        healthTrackingServiceField.set(trackingManager, mockHealthTrackingService)
        
        val healthTracker = trackingManager.obtainEDATracker()

        Assert.assertNotNull(healthTracker)
        Assert.assertEquals(mockEdaTracker, healthTracker)
    }

    @Test
    fun testExtractEdaValues_NotNullValues() {
        val values: MutableMap<ValueKey<*>?, Value<*>?> = HashMap()
        values.put(ValueKey.EdaSet.STATUS, Value(0))
        values.put(
            ValueKey.EdaSet.SKIN_CONDUCTANCE,
            Value(1.27f)
        )
        val dataPoint = DataPoint(values)
        val edaValue = trackingManager.extractEdaValues(dataPoint)

        Assert.assertNotNull(edaValue.status)
        Assert.assertNotNull(edaValue.skinConductance)
        Assert.assertNotNull(edaValue.timestamp)
    }
}
