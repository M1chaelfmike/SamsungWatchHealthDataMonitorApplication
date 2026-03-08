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

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.ConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.DataState
import com.samsung.health.sensorsdksample.edatracking.data.EDAStatus
import com.samsung.health.sensorsdksample.edatracking.data.MessageState
import com.samsung.health.sensorsdksample.edatracking.data.ProgressState
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.EDATrackingTheme
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.PaddingMedium
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.SpacerMedium
import com.samsung.health.sensorsdksample.edatracking.utils.toHumanTime
import com.samsung.health.sensorsdksample.edatracking.viewModel.ContinuousTrackingViewModel
import com.samsung.health.sensorsdksample.edatracking.viewModel.MainViewModel
import com.samsung.health.sensorsdksample.edatracking.viewModel.SkinTempViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val APP_TAG = "EDATracking"
        private const val PERMISSION_REQUEST_CODE = 0
    }

    private val viewModel: MainViewModel by viewModels()
    private val skinTempViewModel: SkinTempViewModel by viewModels()
    private val continuousTrackingViewModel: ContinuousTrackingViewModel by viewModels()
    private var permission: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        requestHealthPermission()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messageState.collect { messageState ->
                    when (messageState) {
                        is MessageState.TrackerNotAvailable -> showMessage(getString(R.string.tracker_unavailable))
                        is MessageState.ResolvableError -> messageState.exception.resolve(this@MainActivity)
                        is MessageState.Error -> showMessage(messageState.errorMessage)
                        is MessageState.PermissionError -> showMessage(getString(R.string.permission_not_granted))
                        is MessageState.TrackerNotInitialized -> showMessage(getString(R.string.tracker_not_initialized))
                        is MessageState.TrackingInUse -> showMessage(getString(R.string.eda_tracking_in_use))
                    }
                }
            }
        }

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            val dataState by viewModel.dataState.collectAsState()
            val progressState by viewModel.progressState.collectAsState()
            val connectionState by viewModel.connectionState.collectAsState()
            val pagerState = rememberPagerState(pageCount = { 3 })
            val settledPage = pagerState.settledPage

            when (connectionState) {
                is ConnectionState.Connected -> showMessage(getString(R.string.connected_to_hts))
                is ConnectionState.Disconnected -> showMessage(getString(R.string.disconnected_from_hts))
            }

            EDATrackingTheme {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colors.background)
                ) {
                    when (it) {
                        0 -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = PaddingMedium),
                                    text = stringResource(R.string.app_title),
                                    style = AppTypography.headlineMedium,
                                    textAlign = TextAlign.Center
                                )

                                when (dataState) {
                                    is DataState.Initial -> {
                                        InitialRow(
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                        )
                                    }

                                    is DataState.DataObtained -> {
                                        val edaValue = (dataState as DataState.DataObtained).value
                                        val skinConductance =
                                            if (edaValue.skinConductance == null) stringResource(R.string.null_value) else String.format(
                                                Locale.getDefault(),
                                                "%.3f",
                                                edaValue.skinConductance
                                            )
                                        val timestamp =
                                            if (edaValue.timestamp == null) stringResource(R.string.null_value) else edaValue.timestamp.toHumanTime()

                                        ResultRow(
                                            modifier = Modifier
                                                .align(Alignment.Center),
                                            skinConductance = skinConductance,
                                            statusDescription = obtainStatusDescription(edaValue.status),
                                            timestamp = timestamp
                                        )
                                    }
                                }

                                if (progressState is ProgressState.Measuring && (progressState as ProgressState.Measuring).isMeasuring) {
                                    Button(stringResource(R.string.stop)) {
                                        stop()
                                    }
                                } else {
                                    Button(stringResource(R.string.start)) {
                                        if (progressState is ProgressState.TrackingDisabled) {
                                            showMessage(getString(R.string.tracker_unavailable))
                                        } else if (connectionState is ConnectionState.Disconnected) {
                                            showMessage(getString(R.string.disconnected_from_hts))
                                        } else {
                                            start()
                                        }
                                    }
                                }
                            }
                        }

                        1 -> {
                            SkinTempPage(
                                viewModel = skinTempViewModel,
                                isActive = settledPage == 1
                            )
                        }

                        2 -> {
                            ContinuousMonitoringPage(
                                viewModel = continuousTrackingViewModel,
                                isActive = settledPage == 2
                            )
                        }
                    }
                }
            }
        }

        viewModel.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        stop()
        viewModel.disconnect()
    }

    private fun requestHealthPermission() {
        if (preparePermission() == null) {
            showMessage(getString(R.string.permission_not_set))
        } else {
            permission = preparePermission()!!

            if (!isPermissionGranted()) {
                requestPermissions(arrayOf(preparePermission()), PERMISSION_REQUEST_CODE)
            }
        }
    }

    fun preparePermission(): String? {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA"
        } else {
            "android.permission.BODY_SENSORS"
        }

        return permission
    }

    private fun isPermissionGranted() = ActivityCompat.checkSelfPermission(
        applicationContext,
        permission!!
    ) == PackageManager.PERMISSION_GRANTED


    private fun start() {
        if (permission == null) {
            showMessage(getString(R.string.permission_not_set))
        } else if (isPermissionGranted()) {
            viewModel.startTracking()
        } else if (shouldShowRequestPermissionRationale(permission!!)) {
            requestHealthPermission()
        } else {
            showMessage(getString(R.string.permission_not_granted))
        }
    }

    private fun stop() {
        viewModel.stopTracking()
    }

    private fun showMessage(message: String?) {
        val messageToShow = message ?: resources.getString(R.string.error_other)

        Toast.makeText(this@MainActivity, messageToShow, Toast.LENGTH_SHORT).show()
    }

    private fun obtainStatusDescription(status: EDAStatus?): String {
        return when (status) {
            EDAStatus.NORMAL -> getString(R.string.status_normal)
            EDAStatus.DETACHED -> getString(R.string.status_detached)
            EDAStatus.LOW_SIGNAL -> getString(R.string.status_low_signal)
            EDAStatus.UNKNOWN -> getString(R.string.null_value)
            else -> getString(R.string.null_value)
        }
    }
}

@Composable
fun ResultRow(
    modifier: Modifier,
    skinConductance: String,
    statusDescription: String,
    timestamp: String
) {

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.value),
                    style = AppTypography.headlineMedium
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.status),
                    style = AppTypography.headlineMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(SpacerMedium))

        Row(
            modifier = modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = skinConductance,
                    style = AppTypography.bodyLarge
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = statusDescription,
                    style = AppTypography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(SpacerMedium))

        Row(
            modifier = modifier
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Timestamp",
                    style = AppTypography.bodySmall,
                    color = Color.Gray
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = timestamp,
                    style = AppTypography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun InitialRow(modifier: Modifier) {
    ResultRow(
        modifier,
        skinConductance = stringResource(R.string.empty_value),
        statusDescription = stringResource(R.string.empty_value),
        timestamp = stringResource(R.string.empty_value)
    )
}

@Composable
fun Button(actionName: String, action: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        FilledTonalButton(
            modifier = Modifier
                .padding(bottom = PaddingMedium)
                .align(Alignment.BottomCenter),
            onClick = {
                action()
            }
        ) {
            Text(
                text = actionName,
                color = Color.Black
            )
        }
    }
}
