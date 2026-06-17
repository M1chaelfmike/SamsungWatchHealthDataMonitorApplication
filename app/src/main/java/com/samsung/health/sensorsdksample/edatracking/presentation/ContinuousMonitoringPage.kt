package com.samsung.health.sensorsdksample.edatracking.presentation

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.health.connect.HealthPermissions
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState
import com.samsung.health.sensorsdksample.edatracking.data.EDAValue
import com.samsung.health.sensorsdksample.edatracking.data.EdaWindowLabel
import com.samsung.health.sensorsdksample.edatracking.data.HeartRateValue
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempValue
import com.samsung.health.sensorsdksample.edatracking.data.WearStatusSnapshot
import com.samsung.health.sensorsdksample.edatracking.config.WatchConfiguration
import com.samsung.health.sensorsdksample.edatracking.pairing.WatchPairingReason
import com.samsung.health.sensorsdksample.edatracking.pairing.WatchPairingState
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.EDATrackingTheme
import com.samsung.health.sensorsdksample.edatracking.viewModel.ContinuousTrackingViewModel
import java.util.Calendar
import java.util.Locale

private data class ContinuousMonitoringUiState(
    val connectionState: ContinuousConnectionState,
    val progressState: ContinuousTrackingProgressState,
    val wearStatusSnapshot: WearStatusSnapshot?,
    val edaValue: EDAValue?,
    val edaLabel: EdaWindowLabel?,
    val lastEdaUpdateAtMillis: Long?,
    val skinTempValue: SkinTempValue?,
    val lastSkinTempUpdateAtMillis: Long?,
    val heartRateValue: HeartRateValue?,
    val lastHeartRateUpdateAtMillis: Long?,
    val heartRateAlertLevel: String,
    val heartRateRealtimeMonitoring: Boolean,
    val heartRateAlertSustainedSeconds: Int,
    val heartRateMonitoringMode: String,
    val heartRateBaselineIntervalMinutes: Int,
    val uploadHost: String,
    val uploadPort: Int,
    val showSuccessPopup: Boolean,
    val showFailurePopup: Boolean,
    val ecgSupported: Boolean,
    val isAnySensorCycleActive: Boolean,
    val isEcgReadyToStart: Boolean,
    val ecgMeasurementRunning: Boolean,
    val ecgLeadOff: Boolean,
    val ecgRemainingSeconds: Int?,
    val ecgCurrentValueMv: Float?,
    val ecgStatusText: String,
    val lastEcgMeasuredAtMillis: Long?,
    val lastEcgValueMv: Float?,
    val lastEcgSampleCount: Int
)

private data class EdaDisplayState(
    val primary: String,
    val secondary: String,
    val footer: String?
)

@Composable
fun ContinuousMonitoringPage(
    viewModel: ContinuousTrackingViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val dataState by viewModel.dataState.collectAsState()
    val progressState by viewModel.progressState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val pairingState by viewModel.pairingState.collectAsState()
    var showSettingsPrompt by remember { mutableStateOf(false) }
    var showUploadTargetDialog by remember { mutableStateOf(false) }
    var uploadHostInput by remember { mutableStateOf(dataState.uploadHost) }
    var uploadPortInput by remember { mutableStateOf(dataState.uploadPort.toString()) }
    val missingPermissionText = remember(context, connectionState, progressState) {
        buildMissingPermissionsText(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = requiredContinuousPermissions().all { permission -> results[permission] == true }
        if (granted) {
            viewModel.startBackgroundTracking(context)
        } else if (
            activity != null &&
            requiredContinuousPermissions().all { permission ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            }
        ) {
            showSettingsPrompt = true
        } else {
            Toast.makeText(context, missingPermissionText, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(viewModel, dataState.showSuccessPopup, dataState.showFailurePopup) {
        viewModel.messageState.collect { messageState ->
            when (messageState) {
                is ContinuousTrackingMessageState.UnsupportedSensors -> {
                    if (dataState.showFailurePopup) {
                        val message = buildUnsupportedSensorsMessage(
                            context = context,
                            edaSupported = messageState.edaSupported,
                            skinTemperatureSupported = messageState.skinTemperatureSupported,
                            heartRateSupported = messageState.heartRateSupported,
                            ppgSupported = messageState.ppgSupported
                        )
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }

                is ContinuousTrackingMessageState.ResolvableError -> {
                    activity?.let { messageState.exception.resolve(it) }
                }

                is ContinuousTrackingMessageState.PermissionError -> {
                    if (dataState.showFailurePopup) {
                        Toast.makeText(context, missingPermissionText, Toast.LENGTH_LONG).show()
                    }
                }

                is ContinuousTrackingMessageState.Error -> {
                    if (dataState.showFailurePopup) {
                        Toast.makeText(
                            context,
                            messageState.errorMessage ?: context.getString(R.string.continuous_error_other),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                is ContinuousTrackingMessageState.Info -> {
                    if (dataState.showSuccessPopup) {
                        Toast.makeText(context, messageState.message, Toast.LENGTH_SHORT).show()
                    }
                }

                is ContinuousTrackingMessageState.TrackingInUse -> {
                    if (dataState.showFailurePopup) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.continuous_eda_in_use),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    LaunchedEffect(pairingState.requiresPairing, connectionState, progressState) {
        if (pairingState.requiresPairing) {
            viewModel.stopBackgroundTracking(context)
            return@LaunchedEffect
        }

        if (
            progressState == ContinuousTrackingProgressState.Tracking ||
            progressState == ContinuousTrackingProgressState.TrackingDisabled
        ) {
            return@LaunchedEffect
        }

        if (hasContinuousPermissions(context)) {
            viewModel.startBackgroundTracking(context)
        } else {
            permissionLauncher.launch(requiredContinuousPermissions())
        }
    }

    if (pairingState.requiresPairing) {
        WatchPairingPage(
            pairingState = pairingState,
            onSkip = { viewModel.skipPairingWithCurrentConfiguration() },
            onRefresh = { viewModel.refreshPairingInfo() },
            modifier = modifier
        )
        return
    }

    if (showSettingsPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showSettingsPrompt = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showSettingsPrompt = false
                        openAppSettings(context)
                    }
                ) {
                    androidx.compose.material3.Text(text = stringResource(R.string.skin_temp_settings))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSettingsPrompt = false }) {
                    androidx.compose.material3.Text(text = stringResource(R.string.skin_temp_not_now))
                }
            },
            title = {
                androidx.compose.material3.Text(text = stringResource(R.string.continuous_permission_title))
            },
            text = {
                androidx.compose.material3.Text(text = missingPermissionText)
            }
        )
    }

    if (showUploadTargetDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUploadTargetDialog = false },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val port = uploadPortInput.toIntOrNull()
                        if (uploadHostInput.isBlank() || port == null || port !in 1..65535) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.continuous_upload_target_invalid),
                                Toast.LENGTH_SHORT
                            ).show()
                            return@TextButton
                        }

                        viewModel.updateUploadTarget(uploadHostInput.trim(), port)
                        showUploadTargetDialog = false
                        Toast.makeText(
                            context,
                            context.getString(R.string.continuous_upload_target_saved),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    androidx.compose.material3.Text(text = stringResource(R.string.continuous_upload_target_save))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showUploadTargetDialog = false }) {
                    androidx.compose.material3.Text(text = stringResource(R.string.skin_temp_not_now))
                }
            },
            title = {
                androidx.compose.material3.Text(text = stringResource(R.string.continuous_upload_target_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.TextField(
                        value = uploadHostInput,
                        onValueChange = { uploadHostInput = it },
                        singleLine = true,
                        label = {
                            androidx.compose.material3.Text(text = stringResource(R.string.continuous_upload_target_host))
                        }
                    )
                    androidx.compose.material3.TextField(
                        value = uploadPortInput,
                        onValueChange = { uploadPortInput = it },
                        singleLine = true,
                        label = {
                            androidx.compose.material3.Text(text = stringResource(R.string.continuous_upload_target_port))
                        }
                    )
                }
            }
        )
    }

    val uiState = ContinuousMonitoringUiState(
        connectionState = connectionState,
        progressState = progressState,
        wearStatusSnapshot = dataState.wearStatusSnapshot,
        edaValue = dataState.edaValue,
        edaLabel = dataState.edaLabel,
        lastEdaUpdateAtMillis = dataState.lastEdaUpdateAtMillis,
        skinTempValue = dataState.skinTempValue,
        lastSkinTempUpdateAtMillis = dataState.lastSkinTempUpdateAtMillis,
        heartRateValue = dataState.heartRateValue,
        lastHeartRateUpdateAtMillis = dataState.lastHeartRateUpdateAtMillis,
        heartRateAlertLevel = dataState.heartRateAlertLevel,
        heartRateRealtimeMonitoring = dataState.heartRateRealtimeMonitoring,
        heartRateAlertSustainedSeconds = dataState.heartRateAlertSustainedSeconds,
        heartRateMonitoringMode = dataState.heartRateMonitoringMode,
        heartRateBaselineIntervalMinutes = dataState.heartRateBaselineIntervalMinutes,
        uploadHost = dataState.uploadHost,
        uploadPort = dataState.uploadPort,
        showSuccessPopup = dataState.showSuccessPopup,
        showFailurePopup = dataState.showFailurePopup,
        ecgSupported = dataState.ecgSupported,
        isAnySensorCycleActive = dataState.isAnySensorCycleActive,
        isEcgReadyToStart = dataState.isEcgReadyToStart,
        ecgMeasurementRunning = dataState.ecgMeasurementRunning,
        ecgLeadOff = dataState.ecgLeadOff,
        ecgRemainingSeconds = dataState.ecgRemainingSeconds,
        ecgCurrentValueMv = dataState.ecgCurrentValueMv,
        ecgStatusText = dataState.ecgStatusText,
        lastEcgMeasuredAtMillis = dataState.lastEcgMeasuredAtMillis,
        lastEcgValueMv = dataState.lastEcgValueMv,
        lastEcgSampleCount = dataState.lastEcgSampleCount
    )

    ContinuousMonitoringContent(
        uiState = uiState,
        pairingState = pairingState,
        editTargetButtonLabel = stringResource(R.string.continuous_upload_target_edit),
        onToggleEcgMeasurement = { viewModel.toggleEcgMeasurement() },
        onToggleSuccessPopup = {
            viewModel.updatePopupPreferences(showSuccessPopup = !dataState.showSuccessPopup)
        },
        onToggleFailurePopup = {
            viewModel.updatePopupPreferences(showFailurePopup = !dataState.showFailurePopup)
        },
        onToggleHeartRateMonitoringMode = {
            val nextMode = if (dataState.heartRateMonitoringMode == "high_risk") "standard" else "high_risk"
            viewModel.updateHeartRateMonitoringMode(nextMode)
        },
        onRefreshPairing = {
            viewModel.refreshPairingInfo()
        },
        onEditTargetClick = {
            uploadHostInput = dataState.uploadHost
            uploadPortInput = dataState.uploadPort.toString()
            showUploadTargetDialog = true
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinuousMonitoringContent(
    uiState: ContinuousMonitoringUiState,
    pairingState: WatchPairingState,
    editTargetButtonLabel: String,
    onToggleEcgMeasurement: () -> Unit,
    onToggleSuccessPopup: () -> Unit,
    onToggleFailurePopup: () -> Unit,
    onToggleHeartRateMonitoringMode: () -> Unit,
    onRefreshPairing: () -> Unit,
    onEditTargetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val edaDisplay = buildEdaDisplayState(
        progressState = uiState.progressState,
        edaValue = uiState.edaValue,
        edaLabel = uiState.edaLabel,
        lastEdaUpdateAtMillis = uiState.lastEdaUpdateAtMillis
    )
    val heartRateStatusLine = when (uiState.heartRateAlertLevel.lowercase(Locale.getDefault())) {
        "critical" -> "CRITICAL realtime ${uiState.heartRateAlertSustainedSeconds}s"
        "warning" -> "WARNING realtime ${uiState.heartRateAlertSustainedSeconds}s"
        else -> {
            val minuteLabel = if (uiState.heartRateBaselineIntervalMinutes == 1) "min" else "mins"
            val modeLabel = if (uiState.heartRateMonitoringMode == "high_risk") "High-risk" else "Standard"
            "$modeLabel baseline ${uiState.heartRateBaselineIntervalMinutes} $minuteLabel"
        }
    }
    val heartRateLines = listOf(
        uiState.heartRateValue?.heartRate?.let { "$it bpm" } ?: "Waiting HR",
        heartRateStatusLine,
        uiState.lastHeartRateUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No HR update"
    )
    val skinTempLines = listOf(
        uiState.skinTempValue?.wristSkinTemperature?.let { String.format(Locale.getDefault(), "WS %.2f°C", it) } ?: "WS --",
        uiState.skinTempValue?.ambientTemperature?.let { String.format(Locale.getDefault(), "AT %.2f°C", it) } ?: "AT --",
        uiState.lastSkinTempUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No TEMP update"
    )
    val targetLines = listOf(
        uiState.uploadHost,
        "Port ${uiState.uploadPort}",
        "HTTP POST /"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> PrimaryWearPage(
                    uiState = uiState,
                    edaDisplay = edaDisplay,
                    heartRateLines = heartRateLines,
                    skinTempLines = skinTempLines
                )

                1 -> EcgMeasurementPage(
                    uiState = uiState,
                    onToggleEcgMeasurement = onToggleEcgMeasurement
                )

                else -> SettingsPage(
                    pairingState = pairingState,
                    targetLines = targetLines,
                    editTargetButtonLabel = editTargetButtonLabel,
                    heartRateMonitoringMode = uiState.heartRateMonitoringMode,
                    heartRateBaselineIntervalMinutes = uiState.heartRateBaselineIntervalMinutes,
                    showSuccessPopup = uiState.showSuccessPopup,
                    showFailurePopup = uiState.showFailurePopup,
                    onRefreshPairing = onRefreshPairing,
                    onEditTargetClick = onEditTargetClick,
                    onToggleHeartRateMonitoringMode = onToggleHeartRateMonitoringMode,
                    onToggleSuccessPopup = onToggleSuccessPopup,
                    onToggleFailurePopup = onToggleFailurePopup
                )
            }
        }

        PageIndicatorRow(
            currentPage = pagerState.currentPage,
            pageCount = 3,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun PrimaryWearPage(
    uiState: ContinuousMonitoringUiState,
    edaDisplay: EdaDisplayState,
    heartRateLines: List<String>,
    skinTempLines: List<String>
) {
    val heartAccent = when (uiState.heartRateAlertLevel.lowercase(Locale.getDefault())) {
        "critical" -> Color(0xFFFF5B6E)
        "warning" -> Color(0xFFFFB74D)
        else -> Color(0xFFFF6F80)
    }
    val isTracking = uiState.progressState == ContinuousTrackingProgressState.Tracking

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        item {
            WearHeaderPill(
                isWorn = uiState.wearStatusSnapshot?.isWorn,
                progressState = uiState.progressState,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            VisualMetricBlock(
                title = "HR",
                primary = heartRateLines.getOrElse(0) { "Waiting HR" },
                secondary = heartRateLines.getOrElse(1) { "Baseline" },
                footer = heartRateLines.getOrNull(2),
                accentColor = heartAccent,
                cardHeight = 106.dp,
                icon = {
                    HeartLineIcon(
                        active = isTracking && uiState.heartRateValue?.heartRate != null,
                        color = heartAccent
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            VisualMetricBlock(
                title = "EDA",
                primary = edaDisplay.primary,
                secondary = edaDisplay.secondary,
                footer = edaDisplay.footer,
                accentColor = Color(0xFF6FE7D1),
                cardHeight = 94.dp,
                icon = {
                    EdaWaveIcon(
                        active = isTracking,
                        color = Color(0xFF6FE7D1)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            VisualMetricBlock(
                title = "TEMP",
                primary = skinTempLines.getOrElse(0) { "WS --" },
                secondary = skinTempLines.getOrElse(1) { "AT --" },
                footer = skinTempLines.getOrNull(2),
                accentColor = Color(0xFFFFD166),
                cardHeight = 96.dp,
                icon = {
                    ThermometerLineIcon(
                        active = isTracking,
                        color = Color(0xFFFFD166)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun EcgMeasurementPage(
    uiState: ContinuousMonitoringUiState,
    onToggleEcgMeasurement: () -> Unit
) {
    val buttonEnabled = uiState.ecgMeasurementRunning || (
        uiState.connectionState == ContinuousConnectionState.Connected &&
            uiState.ecgSupported &&
            uiState.isEcgReadyToStart
        )
    val statusLines = buildList {
        add(uiState.ecgStatusText)
        uiState.ecgRemainingSeconds?.let { add("${it}s remaining") }
        uiState.ecgCurrentValueMv?.let { add(String.format(Locale.getDefault(), "%.2f mV", it)) }
        if (uiState.ecgMeasurementRunning && uiState.ecgLeadOff) {
            add("Keep finger on sensor")
        }
        uiState.lastEcgMeasuredAtMillis?.let { add("Last ${formatClockTime(it)}") }
        if (uiState.lastEcgSampleCount > 0) {
            add("${uiState.lastEcgSampleCount} samples")
        }
    }
    val helperText = when {
        !uiState.ecgSupported -> "ECG not supported"
        uiState.connectionState != ContinuousConnectionState.Connected -> "HTS disconnected"
        !uiState.isEcgReadyToStart && !uiState.ecgMeasurementRunning -> "Wait until HR/TEMP/EDA all finish"
        uiState.ecgMeasurementRunning -> "Auto-send only after 30s ends\nManual stop will not send"
        else -> "Press Start and hold finger for 30s"
    }

    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 36.dp),
        autoCentering = null,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            VisualMetricBlock(
                title = "ECG",
                primary = statusLines.getOrElse(0) { "Ready" },
                secondary = statusLines.getOrNull(1) ?: if (uiState.ecgSupported) "On demand" else "Unavailable",
                footer = statusLines.drop(2).firstOrNull(),
                accentColor = Color(0xFFA7C7FF),
                cardHeight = 112.dp,
                icon = {
                    EcgLineIcon(
                        active = uiState.ecgMeasurementRunning,
                        color = Color(0xFFA7C7FF)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            WearActionButton(
                label = when {
                    uiState.ecgMeasurementRunning -> "STOP ECG"
                    buttonEnabled -> "START ECG"
                    else -> "ECG LOCKED"
                },
                enabled = buttonEnabled,
                onClick = onToggleEcgMeasurement
            )
        }
        item {
            Text(
                text = helperText,
                style = AppTypography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SettingsPage(
    pairingState: WatchPairingState,
    targetLines: List<String>,
    editTargetButtonLabel: String,
    heartRateMonitoringMode: String,
    heartRateBaselineIntervalMinutes: Int,
    showSuccessPopup: Boolean,
    showFailurePopup: Boolean,
    onRefreshPairing: () -> Unit,
    onEditTargetClick: () -> Unit,
    onToggleHeartRateMonitoringMode: () -> Unit,
    onToggleSuccessPopup: () -> Unit,
    onToggleFailurePopup: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 38.dp),
        autoCentering = null,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PairingStatusBlock(
                pairingState = pairingState,
                onRefresh = onRefreshPairing,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            EditableMetricBlock(
                title = "TARGET",
                lines = targetLines,
                buttonLabel = editTargetButtonLabel,
                onEditClick = onEditTargetClick,
                cardHeight = 142.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            PopupConfigBlock(
                heartRateMonitoringMode = heartRateMonitoringMode,
                heartRateBaselineIntervalMinutes = heartRateBaselineIntervalMinutes,
                onToggleHeartRateMonitoringMode = onToggleHeartRateMonitoringMode,
                showSuccessPopup = showSuccessPopup,
                showFailurePopup = showFailurePopup,
                onToggleSuccessPopup = onToggleSuccessPopup,
                onToggleFailurePopup = onToggleFailurePopup,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Preview(
    name = "Wear Tracking",
    device = Devices.WEAR_OS_SMALL_ROUND,
    showSystemUi = true,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun ContinuousMonitoringContentPreviewTracking() {
    EDATrackingTheme {
        ContinuousMonitoringContent(
            uiState = previewUiState(),
            pairingState = previewPairingState(),
            editTargetButtonLabel = "Edit target",
            onToggleEcgMeasurement = {},
            onToggleSuccessPopup = {},
            onToggleFailurePopup = {},
            onToggleHeartRateMonitoringMode = {},
            onRefreshPairing = {},
            onEditTargetClick = {}
        )
    }
}

@Preview(
    name = "Wear Idle",
    device = Devices.WEAR_OS_SMALL_ROUND,
    showSystemUi = true,
    showBackground = true,
    backgroundColor = 0xFF000000
)
@Composable
private fun ContinuousMonitoringContentPreviewIdle() {
    EDATrackingTheme {
        ContinuousMonitoringContent(
            uiState = previewUiState(
                connectionState = ContinuousConnectionState.Connected,
                progressState = ContinuousTrackingProgressState.Idle,
                wearStatusSnapshot = null,
                edaValue = null,
                edaLabel = null,
                lastEdaUpdateAtMillis = null,
                skinTempValue = null,
                lastSkinTempUpdateAtMillis = null,
                heartRateValue = null,
                lastHeartRateUpdateAtMillis = null,
                ecgStatusText = "Ready"
            ),
            pairingState = previewPairingState(),
            editTargetButtonLabel = "Edit target",
            onToggleEcgMeasurement = {},
            onToggleSuccessPopup = {},
            onToggleFailurePopup = {},
            onToggleHeartRateMonitoringMode = {},
            onRefreshPairing = {},
            onEditTargetClick = {}
        )
    }
}

private fun previewPairingState(): WatchPairingState {
    return WatchPairingState(
        requiresPairing = false,
        canSkip = true,
        receiverRunning = true,
        receiverPort = 8765,
        watchIp = "192.168.0.24",
        macAddress = "A1:B2:C3:D4:E5:F6",
        pairingCode = "123456",
        configuration = WatchConfiguration(
            watchId = "real-watch-001",
            serverHost = "192.168.0.5",
            serverPort = 3100,
            paired = true,
            hasStoredTarget = true
        ),
        reason = WatchPairingReason.PAIRED,
        message = "Configuration ready"
    )
}

private fun buildEdaDisplayState(
    progressState: ContinuousTrackingProgressState,
    edaValue: EDAValue?,
    edaLabel: EdaWindowLabel?,
    lastEdaUpdateAtMillis: Long?
): EdaDisplayState {
    val footer = lastEdaUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No EDA update"
    return when (progressState) {
        ContinuousTrackingProgressState.Tracking -> when (edaLabel) {
            EdaWindowLabel.DETACHED -> EdaDisplayState("Wear check", "Adjust watch fit", footer)
            EdaWindowLabel.LOW_SIGNAL -> EdaDisplayState("Low signal", "Keep wrist steady", footer)
            EdaWindowLabel.STABLE,
            EdaWindowLabel.RISING,
            EdaWindowLabel.RECOVERING,
            EdaWindowLabel.VARIABLE -> {
                val raw = edaValue?.skinConductance
                EdaDisplayState(
                    primary = edaArousalLabel(raw) ?: "Listening",
                    secondary = edaReadingLine(raw, edaLabel),
                    footer = footer
                )
            }
            EdaWindowLabel.WAITING,
            null -> EdaDisplayState("Listening", edaReadingLine(edaValue?.skinConductance, edaLabel), footer)
        }

        ContinuousTrackingProgressState.Idle -> EdaDisplayState("Waiting", "Start tracking", footer)
        ContinuousTrackingProgressState.TrackingDisabled -> EdaDisplayState("Disabled", "Sensor unavailable", footer)
    }
}

private fun edaArousalLabel(skinConductance: Float?): String? {
    return when {
        skinConductance == null -> null
        skinConductance > 5f -> "Signal artifact"
        skinConductance < 0.3f -> "Calm"
        skinConductance < 1.0f -> "Baseline"
        skinConductance < 2.0f -> "Elevated"
        else -> "High"
    }
}

private fun edaReadingLine(skinConductance: Float?, edaLabel: EdaWindowLabel?): String {
    val reading = skinConductance?.let { String.format(Locale.getDefault(), "%.3f uS", it) } ?: "Waiting signal"
    val trend = when (edaLabel) {
        EdaWindowLabel.STABLE -> "Stable"
        EdaWindowLabel.RISING -> "Rising"
        EdaWindowLabel.RECOVERING -> "Recovering"
        EdaWindowLabel.VARIABLE -> "Variable"
        else -> null
    }
    return listOfNotNull(reading, trend).joinToString(" - ")
}

private fun formatClockTime(timeMillis: Long): String {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = timeMillis
    }
    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
        calendar.get(Calendar.SECOND)
    )
}

@Composable
private fun WearHeaderPill(
    isWorn: Boolean?,
    progressState: ContinuousTrackingProgressState,
    modifier: Modifier = Modifier
) {
    val active = isWorn == true
    val statusText = when {
        isWorn == false -> "Off wrist"
        progressState == ContinuousTrackingProgressState.Tracking -> "Collecting"
        progressState == ContinuousTrackingProgressState.TrackingDisabled -> "Sensors off"
        else -> "Ready"
    }
    val accent = when {
        isWorn == false -> Color(0xFFFF8A8A)
        progressState == ContinuousTrackingProgressState.Tracking -> Color(0xFF9AE6B4)
        else -> Color(0xFFA7C7FF)
    }
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF101318))
            .border(width = 1.dp, color = accent.copy(alpha = 0.45f), shape = shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        ContinuousStatusIndicator(
            active = active,
            inactiveColor = if (isWorn == null) Color(0xFF6F7682) else Color(0xFFFF8A8A)
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = statusText,
            style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PairingStatusBlock(
    pairingState: WatchPairingState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = pairingState.configuration
    val accentColor = pairingAccentColor(pairingState)
    val shape = RoundedCornerShape(8.dp)
    val statusText = if (configuration.paired && !pairingState.requiresPairing) {
        "Paired"
    } else {
        "Needs setup"
    }
    val receiverText = if (pairingState.receiverRunning) {
        "Receiver ${pairingState.receiverPort}"
    } else {
        "Receiver off"
    }
    val watchIp = pairingState.watchIp ?: "IP unavailable"
    val pairingCode = pairingState.pairingCode.ifBlank { "Unavailable" }
    val target = "${configuration.serverHost}:${configuration.serverPort}"
    val detailText = pairingState.message ?: pairingReasonLabel(pairingState.reason)

    Box(
        modifier = modifier
            .height(214.dp)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF151922), Color(0xFF0C0F15))
                ),
                shape = shape
            )
            .border(width = 1.dp, color = accentColor.copy(alpha = 0.38f), shape = shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "PAIRING",
                style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContinuousStatusIndicator(
                    active = configuration.paired && pairingState.receiverRunning,
                    inactiveColor = Color(0xFFFF8A8A)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "$statusText | $receiverText",
                    style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = detailText,
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color(0xFFD8DEE9)
            )
            Spacer(modifier = Modifier.height(5.dp))
            PairingInfoLine(label = "Watch", value = configuration.watchId)
            PairingInfoLine(label = "Code", value = pairingCode)
            PairingInfoLine(label = "Target", value = target)
            PairingInfoLine(label = "IP", value = watchIp)
            Spacer(modifier = Modifier.height(6.dp))
            InlineToggleButton(
                label = "Refresh info",
                active = true,
                accentColor = accentColor,
                onClick = onRefresh
            )
        }
    }
}

@Composable
private fun PairingInfoLine(
    label: String,
    value: String
) {
    Text(
        text = "$label: $value",
        style = AppTypography.bodySmall,
        textAlign = TextAlign.Center,
        color = Color(0xFFB8C7D9)
    )
}

private fun pairingReasonLabel(reason: WatchPairingReason): String {
    return when (reason) {
        WatchPairingReason.FIRST_LAUNCH -> "First launch"
        WatchPairingReason.NETWORK_CHANGED -> "Network changed"
        WatchPairingReason.WAITING_FOR_CONFIG -> "Waiting for server config"
        WatchPairingReason.PAIRED -> "Configuration ready"
        WatchPairingReason.RECEIVER_ERROR -> "Receiver error"
    }
}

private fun pairingAccentColor(pairingState: WatchPairingState): Color {
    return when {
        pairingState.reason == WatchPairingReason.RECEIVER_ERROR -> Color(0xFFFF8A8A)
        pairingState.requiresPairing -> Color(0xFFFFB74D)
        pairingState.configuration.paired -> Color(0xFF9AE6B4)
        else -> Color(0xFFA7C7FF)
    }
}

@Composable
private fun LabeledStatusIndicator(
    label: String,
    active: Boolean,
    inactiveColor: Color = Color(0xFFE5E5E5),
    unknown: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ContinuousStatusIndicator(
            active = active,
            inactiveColor = if (unknown) Color(0xFFD5D5D5) else inactiveColor
        )
        Text(
            text = label,
            style = AppTypography.bodySmall,
            color = Color.White,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PageFrame(
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        content()
    }
}

@Composable
private fun PageIndicatorRow(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == currentPage) 8.dp else 6.dp)
                    .background(
                        color = if (index == currentPage) Color.White else Color(0xFF5C5C5C),
                        shape = CircleShape
                    )
            )
        }
    }
}

@Composable
private fun WearActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    val backgroundColor = if (enabled) Color(0xFFEBF7D9) else Color(0xFF2A2A2A)
    val borderColor = if (enabled) Color(0xFF5E8E2E) else Color(0xFF6E6E6E)
    val textColor = if (enabled) Color(0xFF183A00) else Color(0xFFCCCCCC)
    Box(
        modifier = Modifier
            .widthIn(min = 118.dp)
            .border(width = 2.dp, color = borderColor, shape = shape)
            .background(
                color = backgroundColor,
                shape = shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = AppTypography.bodySmall,
            textAlign = TextAlign.Center,
            color = textColor
        )
    }
}

@Composable
private fun VisualMetricBlock(
    title: String,
    primary: String,
    secondary: String,
    footer: String?,
    accentColor: Color,
    cardHeight: Dp,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(cardHeight)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF14171D),
                        Color(0xFF0B0D11)
                    )
                ),
                shape = shape
            )
            .border(width = 1.dp, color = accentColor.copy(alpha = 0.38f), shape = shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f))
                    .border(width = 1.dp, color = accentColor.copy(alpha = 0.38f), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Start,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = primary,
                    style = AppTypography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Start,
                    color = Color.White
                )
                Text(
                    text = secondary,
                    style = AppTypography.bodySmall,
                    textAlign = TextAlign.Start,
                    color = Color(0xFFD8DEE9)
                )
                if (!footer.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = footer,
                        style = AppTypography.bodySmall,
                        textAlign = TextAlign.Start,
                        color = Color(0xFF8D96A5)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartLineIcon(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "heart-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 520, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heart-scale"
    )
    val scale = if (active) pulse else 1f

    Canvas(
        modifier = modifier
            .size(34.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        val w = size.width
        val h = size.height
        val heart = Path().apply {
            moveTo(w * 0.50f, h * 0.82f)
            cubicTo(w * 0.10f, h * 0.57f, w * 0.10f, h * 0.22f, w * 0.32f, h * 0.20f)
            cubicTo(w * 0.44f, h * 0.19f, w * 0.50f, h * 0.31f, w * 0.50f, h * 0.31f)
            cubicTo(w * 0.50f, h * 0.31f, w * 0.56f, h * 0.19f, w * 0.68f, h * 0.20f)
            cubicTo(w * 0.90f, h * 0.22f, w * 0.90f, h * 0.57f, w * 0.50f, h * 0.82f)
        }
        drawPath(
            path = heart,
            color = color,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun EdaWaveIcon(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "eda-wave")
    val glow by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "eda-glow"
    )

    Canvas(modifier = modifier.size(36.dp)) {
        val w = size.width
        val h = size.height
        val centerY = h * 0.54f
        drawCircle(
            color = color.copy(alpha = if (active) glow * 0.25f else 0.10f),
            radius = size.minDimension * 0.46f
        )
        val wave = Path().apply {
            moveTo(w * 0.08f, centerY)
            cubicTo(w * 0.18f, h * 0.26f, w * 0.28f, h * 0.82f, w * 0.39f, centerY)
            cubicTo(w * 0.50f, h * 0.26f, w * 0.60f, h * 0.82f, w * 0.72f, centerY)
            cubicTo(w * 0.80f, h * 0.36f, w * 0.88f, h * 0.48f, w * 0.94f, centerY)
        }
        drawPath(
            path = wave,
            color = color,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
    }
}

@Composable
private fun ThermometerLineIcon(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "temp-glow")
    val heat by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "temp-heat"
    )
    val alpha = if (active) heat else 0.42f

    Canvas(modifier = modifier.size(36.dp)) {
        val x = size.width * 0.50f
        val top = size.height * 0.16f
        val bottom = size.height * 0.68f
        val bulbRadius = size.minDimension * 0.16f
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(x, top),
            end = androidx.compose.ui.geometry.Offset(x, bottom),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = bulbRadius,
            center = androidx.compose.ui.geometry.Offset(x, size.height * 0.76f)
        )
        drawCircle(
            color = color,
            radius = bulbRadius,
            center = androidx.compose.ui.geometry.Offset(x, size.height * 0.76f),
            style = Stroke(width = 2.2.dp.toPx())
        )
        listOf(0.26f, 0.42f, 0.58f).forEach { yRatio ->
            drawLine(
                color = color.copy(alpha = 0.65f),
                start = androidx.compose.ui.geometry.Offset(size.width * 0.58f, size.height * yRatio),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.74f, size.height * yRatio),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun EcgLineIcon(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "ecg-sweep")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 1250)),
        label = "ecg-dot"
    )

    Canvas(modifier = modifier.size(38.dp)) {
        val w = size.width
        val h = size.height
        val y = h * 0.55f
        val path = Path().apply {
            moveTo(w * 0.04f, y)
            lineTo(w * 0.22f, y)
            lineTo(w * 0.30f, h * 0.38f)
            lineTo(w * 0.38f, h * 0.74f)
            lineTo(w * 0.48f, h * 0.18f)
            lineTo(w * 0.58f, y)
            lineTo(w * 0.94f, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = 2.4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
        )
        if (active) {
            drawCircle(
                color = color,
                radius = 2.7.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(w * sweep, y)
            )
        }
    }
}

@Composable
private fun EditableMetricBlock(
    title: String,
    lines: List<String>,
    buttonLabel: String,
    onEditClick: () -> Unit,
    cardHeight: Dp,
    modifier: Modifier = Modifier
) {
    val accentColor = Color(0xFFA7C7FF)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(cardHeight)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF151922), Color(0xFF0C0F15))
                ),
                shape = shape
            )
            .border(width = 1.dp, color = accentColor.copy(alpha = 0.38f), shape = shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = if (index == 0) {
                        AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                    } else {
                        AppTypography.bodySmall
                    },
                    textAlign = TextAlign.Center,
                    color = if (index == 0) Color.White else Color(0xFFD8DEE9)
                )
                if (index != lines.lastIndex) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
            Spacer(modifier = Modifier.height(7.dp))
            InlineToggleButton(
                label = buttonLabel,
                active = true,
                accentColor = accentColor,
                onClick = onEditClick
            )
        }
    }
}

@Composable
private fun PopupConfigBlock(
    heartRateMonitoringMode: String,
    heartRateBaselineIntervalMinutes: Int,
    onToggleHeartRateMonitoringMode: () -> Unit,
    showSuccessPopup: Boolean,
    showFailurePopup: Boolean,
    onToggleSuccessPopup: () -> Unit,
    onToggleFailurePopup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isHighRiskMode = heartRateMonitoringMode == "high_risk"
    val accentColor = if (isHighRiskMode) Color(0xFFFFB74D) else Color(0xFF9AE6B4)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = modifier
            .height(214.dp)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF151922), Color(0xFF0C0F15))
                ),
                shape = shape
            )
            .border(width = 1.dp, color = accentColor.copy(alpha = 0.34f), shape = shape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "HR MODE",
                style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isHighRiskMode) {
                    "High-risk baseline: 1 min"
                } else {
                    "Standard baseline: ${heartRateBaselineIntervalMinutes} mins"
                },
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color(0xFFD8DEE9)
            )
            Spacer(modifier = Modifier.height(4.dp))
            InlineToggleButton(
                label = if (isHighRiskMode) "Use standard" else "Use high-risk",
                active = isHighRiskMode,
                accentColor = accentColor,
                onClick = onToggleHeartRateMonitoringMode
            )
            Spacer(modifier = Modifier.height(9.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.10f))
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "POPUP",
                style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                color = Color(0xFFA7C7FF)
            )
            Spacer(modifier = Modifier.height(4.dp))
            InlineToggleButton(
                label = if (showSuccessPopup) "Success on" else "Success off",
                active = showSuccessPopup,
                accentColor = Color(0xFF9AE6B4),
                onClick = onToggleSuccessPopup
            )
            Spacer(modifier = Modifier.height(6.dp))
            InlineToggleButton(
                label = if (showFailurePopup) "Failure on" else "Failure off",
                active = showFailurePopup,
                accentColor = Color(0xFFFF8A8A),
                onClick = onToggleFailurePopup
            )
        }
    }
}

@Composable
private fun InlineToggleButton(
    label: String,
    active: Boolean,
    accentColor: Color = Color(0xFFA7C7FF),
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    val backgroundColor = if (active) accentColor.copy(alpha = 0.22f) else Color(0xFF1A1F27)
    val borderColor = if (active) accentColor.copy(alpha = 0.72f) else Color(0xFF4B5563)
    val textColor = if (active) Color.White else Color(0xFFD8DEE9)

    Box(
        modifier = Modifier
            .border(width = 2.dp, color = borderColor, shape = shape)
            .background(color = backgroundColor, shape = shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = AppTypography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            color = textColor
        )
    }
}

@Composable
private fun ContinuousStatusIndicator(
    active: Boolean,
    inactiveColor: Color = Color(0xFFE5E5E5)
) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = if (active) Color(0xFFB8E6C2) else inactiveColor,
                shape = CircleShape
            )
    )
}

private fun requiredContinuousPermissions(): Array<String> {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        arrayOf(
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
            HealthPermissions.READ_SKIN_TEMPERATURE,
            HealthPermissions.READ_HEART_RATE
        )
    } else {
        arrayOf(Manifest.permission.BODY_SENSORS)
    }

    val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        emptyArray()
    }

    val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray()
    }

    return permissions + bluetoothPermissions + notificationPermission
}

private fun hasContinuousPermissions(context: Context): Boolean {
    return requiredContinuousPermissions().all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun buildMissingPermissionsText(context: Context): String {
    val missingPermissions = requiredContinuousPermissions().filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }

    if (missingPermissions.isEmpty()) {
        return context.getString(R.string.continuous_permission_required)
    }

    val permissionNames = missingPermissions.joinToString("\n") { permission ->
        when (permission) {
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA" -> {
                "READ_ADDITIONAL_HEALTH_DATA"
            }

            HealthPermissions.READ_SKIN_TEMPERATURE -> "READ_SKIN_TEMPERATURE"
            HealthPermissions.READ_HEART_RATE -> "READ_HEART_RATE"
            Manifest.permission.BODY_SENSORS -> "BODY_SENSORS"
            Manifest.permission.POST_NOTIFICATIONS -> "POST_NOTIFICATIONS"
            Manifest.permission.BLUETOOTH_ADVERTISE -> "BLUETOOTH_ADVERTISE"
            Manifest.permission.BLUETOOTH_CONNECT -> "BLUETOOTH_CONNECT"
            else -> permission
        }
    }

    return context.getString(R.string.continuous_permission_missing_detail, permissionNames)
}

private fun buildUnsupportedSensorsMessage(
    context: Context,
    edaSupported: Boolean,
    skinTemperatureSupported: Boolean,
    heartRateSupported: Boolean,
    ppgSupported: Boolean
): String {
    val missingSensors = mutableListOf<String>()
    if (!edaSupported) {
        missingSensors += "EDA"
    }
    if (!skinTemperatureSupported) {
        missingSensors += "ST"
    }
    if (!heartRateSupported) {
        missingSensors += "HR"
    }

    return context.getString(R.string.continuous_missing_sensors, missingSensors.joinToString(", "))
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.fromParts("package", context.packageName, null)
    context.startActivity(intent)
}

private fun previewUiState(
    connectionState: ContinuousConnectionState = ContinuousConnectionState.Connected,
    progressState: ContinuousTrackingProgressState = ContinuousTrackingProgressState.Tracking,
    wearStatusSnapshot: WearStatusSnapshot? = WearStatusSnapshot(isWorn = true, changedAtMillis = 1742288400000L),
    edaValue: EDAValue? = EDAValue(skinConductance = 0.72f, status = null, timestamp = 1742288400000L),
    edaLabel: EdaWindowLabel? = EdaWindowLabel.STABLE,
    lastEdaUpdateAtMillis: Long? = 1742288400000L,
    skinTempValue: SkinTempValue? = SkinTempValue(
        ambientTemperature = 24.6f,
        wristSkinTemperature = 32.4f,
        status = SkinTempStatus.SUCCESSFUL_MEASUREMENT
    ),
    lastSkinTempUpdateAtMillis: Long? = 1742288400000L,
    heartRateValue: HeartRateValue? = HeartRateValue(heartRate = 78, status = 1, timestamp = 1742288400000L),
    lastHeartRateUpdateAtMillis: Long? = 1742288400000L,
    heartRateAlertLevel: String = "normal",
    heartRateRealtimeMonitoring: Boolean = false,
    heartRateAlertSustainedSeconds: Int = 0,
    heartRateMonitoringMode: String = "standard",
    heartRateBaselineIntervalMinutes: Int = 3,
    showSuccessPopup: Boolean = false,
    showFailurePopup: Boolean = true,
    ecgSupported: Boolean = true,
    isAnySensorCycleActive: Boolean = false,
    isEcgReadyToStart: Boolean = true,
    ecgMeasurementRunning: Boolean = false,
    ecgLeadOff: Boolean = false,
    ecgRemainingSeconds: Int? = null,
    ecgCurrentValueMv: Float? = null,
    ecgStatusText: String = "Ready",
    lastEcgMeasuredAtMillis: Long? = null,
    lastEcgValueMv: Float? = null,
    lastEcgSampleCount: Int = 0
): ContinuousMonitoringUiState {
    return ContinuousMonitoringUiState(
        connectionState = connectionState,
        progressState = progressState,
        wearStatusSnapshot = wearStatusSnapshot,
        edaValue = edaValue,
        edaLabel = edaLabel,
        lastEdaUpdateAtMillis = lastEdaUpdateAtMillis,
        skinTempValue = skinTempValue,
        lastSkinTempUpdateAtMillis = lastSkinTempUpdateAtMillis,
        heartRateValue = heartRateValue,
        lastHeartRateUpdateAtMillis = lastHeartRateUpdateAtMillis,
        heartRateAlertLevel = heartRateAlertLevel,
        heartRateRealtimeMonitoring = heartRateRealtimeMonitoring,
        heartRateAlertSustainedSeconds = heartRateAlertSustainedSeconds,
        heartRateMonitoringMode = heartRateMonitoringMode,
        heartRateBaselineIntervalMinutes = heartRateBaselineIntervalMinutes,
        uploadHost = "192.168.0.5",
        uploadPort = 5000,
        showSuccessPopup = showSuccessPopup,
        showFailurePopup = showFailurePopup,
        ecgSupported = ecgSupported,
        isAnySensorCycleActive = isAnySensorCycleActive,
        isEcgReadyToStart = isEcgReadyToStart,
        ecgMeasurementRunning = ecgMeasurementRunning,
        ecgLeadOff = ecgLeadOff,
        ecgRemainingSeconds = ecgRemainingSeconds,
        ecgCurrentValueMv = ecgCurrentValueMv,
        ecgStatusText = ecgStatusText,
        lastEcgMeasuredAtMillis = lastEcgMeasuredAtMillis,
        lastEcgValueMv = lastEcgValueMv,
        lastEcgSampleCount = lastEcgSampleCount
    )
}
