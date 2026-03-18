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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.samsung.health.sensorsdksample.edatracking.data.EdaWindowLabel
import com.samsung.health.sensorsdksample.edatracking.data.HeartRateValue
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempValue
import com.samsung.health.sensorsdksample.edatracking.data.WearStatusSnapshot
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.EDATrackingTheme
import com.samsung.health.sensorsdksample.edatracking.viewModel.ContinuousTrackingViewModel
import java.util.Calendar
import java.util.Locale

private data class ContinuousMonitoringUiState(
    val connectionState: ContinuousConnectionState,
    val progressState: ContinuousTrackingProgressState,
    val wearStatusSnapshot: WearStatusSnapshot?,
    val edaLabel: EdaWindowLabel?,
    val lastEdaUpdateAtMillis: Long?,
    val skinTempValue: SkinTempValue?,
    val lastSkinTempUpdateAtMillis: Long?,
    val heartRateValue: HeartRateValue?,
    val lastHeartRateUpdateAtMillis: Long?,
    val uploadHost: String,
    val uploadPort: Int,
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

    LaunchedEffect(viewModel) {
        viewModel.messageState.collect { messageState ->
            when (messageState) {
                is ContinuousTrackingMessageState.UnsupportedSensors -> {
                    val message = buildUnsupportedSensorsMessage(
                        context = context,
                        edaSupported = messageState.edaSupported,
                        skinTemperatureSupported = messageState.skinTemperatureSupported,
                        heartRateSupported = messageState.heartRateSupported,
                        ppgSupported = messageState.ppgSupported
                    )
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }

                is ContinuousTrackingMessageState.ResolvableError -> {
                    activity?.let { messageState.exception.resolve(it) }
                }

                is ContinuousTrackingMessageState.PermissionError -> {
                    Toast.makeText(context, missingPermissionText, Toast.LENGTH_LONG).show()
                }

                is ContinuousTrackingMessageState.Error -> {
                    Toast.makeText(
                        context,
                        messageState.errorMessage ?: context.getString(R.string.continuous_error_other),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is ContinuousTrackingMessageState.Info -> {
                    Toast.makeText(context, messageState.message, Toast.LENGTH_SHORT).show()
                }

                is ContinuousTrackingMessageState.TrackingInUse -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.continuous_eda_in_use),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(connectionState, progressState) {
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
        edaLabel = dataState.edaLabel,
        lastEdaUpdateAtMillis = dataState.lastEdaUpdateAtMillis,
        skinTempValue = dataState.skinTempValue,
        lastSkinTempUpdateAtMillis = dataState.lastSkinTempUpdateAtMillis,
        heartRateValue = dataState.heartRateValue,
        lastHeartRateUpdateAtMillis = dataState.lastHeartRateUpdateAtMillis,
        uploadHost = dataState.uploadHost,
        uploadPort = dataState.uploadPort,
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
        editTargetButtonLabel = stringResource(R.string.continuous_upload_target_edit),
        onToggleEcgMeasurement = { viewModel.toggleEcgMeasurement() },
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
    editTargetButtonLabel: String,
    onToggleEcgMeasurement: () -> Unit,
    onEditTargetClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val edaLines = listOf(
        edaStatusLabel(uiState.progressState, uiState.edaLabel),
        uiState.lastEdaUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No EDA update"
    )
    val heartRateLines = listOf(
        uiState.heartRateValue?.heartRate?.let { "$it bpm" } ?: "Waiting HR",
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
                    edaLines = edaLines,
                    heartRateLines = heartRateLines,
                    skinTempLines = skinTempLines
                )

                1 -> EcgMeasurementPage(
                    uiState = uiState,
                    onToggleEcgMeasurement = onToggleEcgMeasurement
                )

                else -> PageFrame {
                    EditableMetricBlock(
                        title = "TARGET",
                        lines = targetLines,
                        buttonLabel = editTargetButtonLabel,
                        onEditClick = onEditTargetClick,
                        cardHeight = 132.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
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
    edaLines: List<String>,
    heartRateLines: List<String>,
    skinTempLines: List<String>
) {
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabeledStatusIndicator(
                    label = "WEAR",
                    active = uiState.wearStatusSnapshot?.isWorn == true,
                    inactiveColor = Color(0xFFE39B9B),
                    unknown = uiState.wearStatusSnapshot == null
                )
            }
        }
        item {
            ContinuousMetricBlock(
                title = "EDA",
                lines = edaLines,
                cardHeight = 88.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ContinuousMetricBlock(
                title = "HR",
                lines = heartRateLines,
                cardHeight = 88.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ContinuousMetricBlock(
                title = "TEMP",
                lines = skinTempLines,
                cardHeight = 96.dp,
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

    PageFrame {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ContinuousMetricBlock(
                title = "ECG",
                lines = if (statusLines.isEmpty()) listOf("Ready") else statusLines,
                cardHeight = 104.dp,
                modifier = Modifier.fillMaxWidth()
            )
            WearActionButton(
                label = when {
                    uiState.ecgMeasurementRunning -> "STOP ECG"
                    buttonEnabled -> "START ECG"
                    else -> "ECG LOCKED"
                },
                enabled = buttonEnabled,
                onClick = onToggleEcgMeasurement
            )
            Text(
                text = helperText,
                style = AppTypography.bodySmall,
                color = Color.White,
                textAlign = TextAlign.Center
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
            editTargetButtonLabel = "Edit target",
            onToggleEcgMeasurement = {},
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
                edaLabel = null,
                lastEdaUpdateAtMillis = null,
                skinTempValue = null,
                lastSkinTempUpdateAtMillis = null,
                heartRateValue = null,
                lastHeartRateUpdateAtMillis = null,
                ecgStatusText = "Ready"
            ),
            editTargetButtonLabel = "Edit target",
            onToggleEcgMeasurement = {},
            onEditTargetClick = {}
        )
    }
}

private fun edaStatusLabel(
    progressState: ContinuousTrackingProgressState,
    edaLabel: EdaWindowLabel?
): String {
    return when (progressState) {
        ContinuousTrackingProgressState.Tracking -> when (edaLabel) {
            EdaWindowLabel.DETACHED -> "Wear check"
            EdaWindowLabel.LOW_SIGNAL -> "Low signal"
            EdaWindowLabel.STABLE,
            EdaWindowLabel.RISING,
            EdaWindowLabel.RECOVERING,
            EdaWindowLabel.VARIABLE -> "Running"
            EdaWindowLabel.WAITING,
            null -> "Listening"
        }

        ContinuousTrackingProgressState.Idle -> "Waiting"
        ContinuousTrackingProgressState.TrackingDisabled -> "Disabled"
    }
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
private fun ContinuousMetricBlock(
    title: String,
    lines: List<String>,
    cardHeight: Dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(cardHeight)
            .background(
                color = Color(0xFFF3F3F3),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = AppTypography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Black
                )
                if (index != lines.lastIndex) {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
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
    Box(
        modifier = modifier
            .height(cardHeight)
            .background(
                color = Color(0xFFF3F3F3),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            lines.forEach { line ->
                Text(
                    text = line,
                    style = AppTypography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            Box(
                modifier = Modifier
                    .border(
                        width = 2.dp,
                        color = Color(0xFF3A5F8A),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .background(
                        color = Color(0xFFDCEAF8),
                        shape = RoundedCornerShape(999.dp)
                    )
                    .clickable(onClick = onEditClick)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = buttonLabel,
                    style = AppTypography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color.Black
                )
            }
        }
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

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions + Manifest.permission.POST_NOTIFICATIONS
    } else {
        permissions
    }
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
        edaLabel = edaLabel,
        lastEdaUpdateAtMillis = lastEdaUpdateAtMillis,
        skinTempValue = skinTempValue,
        lastSkinTempUpdateAtMillis = lastSkinTempUpdateAtMillis,
        heartRateValue = heartRateValue,
        lastHeartRateUpdateAtMillis = lastHeartRateUpdateAtMillis,
        uploadHost = "192.168.0.5",
        uploadPort = 5000,
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
