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
package com.samsung.health.sensorsdksample.edatracking.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.EDATrackingTheme
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.PaddingMedium
import com.samsung.health.sensorsdksample.edatracking.viewModel.ContinuousTrackingViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.R

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val APP_TAG = "EDATracking"
    }

    private val continuousTrackingViewModel: ContinuousTrackingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            EDATrackingTheme {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = PaddingMedium),
                        text = stringResource(R.string.continuous_page_title),
                        style = AppTypography.headlineMedium,
                        textAlign = TextAlign.Center
                    )

                    ContinuousMonitoringPage(
                        viewModel = continuousTrackingViewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = PaddingMedium * 2)
                    )
                }
            }
        }
    }
}
