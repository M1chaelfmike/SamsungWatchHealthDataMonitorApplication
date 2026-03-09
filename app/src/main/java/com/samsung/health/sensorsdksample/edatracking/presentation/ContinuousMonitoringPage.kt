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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState
import com.samsung.health.sensorsdksample.edatracking.data.EdaWindowLabel
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.data.UploadedSnapshot
import com.samsung.health.sensorsdksample.edatracking.data.WearStatusSnapshot
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.PaddingMedium
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.SpacerMedium
import com.samsung.health.sensorsdksample.edatracking.viewModel.ContinuousTrackingViewModel
import java.util.Locale

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
    val wearStatusSnapshot = dataState.wearStatusSnapshot
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
        } else if (activity != null && requiredContinuousPermissions().all { permission ->
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
                    Toast.makeText(context, context.getString(R.string.continuous_eda_in_use), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(connectionState, progressState) {
        if (progressState == ContinuousTrackingProgressState.Tracking || progressState == ContinuousTrackingProgressState.TrackingDisabled) {
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

    val edaValue = dataState.edaValue
    val skinTempValue = dataState.skinTempValue
    val heartRateValue = dataState.heartRateValue
    val liveHeartRateValue = dataState.liveHeartRateValue
    val lastUploadedSnapshot = dataState.lastUploadedSnapshot

    val edaLines = listOf(
        edaValue?.skinConductance?.let { String.format(Locale.getDefault(), "%.3f", it) } ?: "Waiting EDA",
        dataState.edaLabel?.let { "Label ${it.name}" } ?: "Listening",
        "Valid ${dataState.edaValidSampleCount} pts",
        dataState.lastEdaUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No EDA update"
    )

    val skinTempLines = listOf(
        skinTempValue?.wristSkinTemperature?.let { String.format(Locale.getDefault(), "WS %.2f°C", it) } ?: "Waiting TEMP",
        skinTempStatusLabel(skinTempValue?.status),
        skinTempValue?.ambientTemperature?.let { String.format(Locale.getDefault(), "AT %.2f°C", it) } ?: "AT --",
        dataState.lastSkinTempUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No TEMP update"
    )

    val heartRateLines = listOf(
        heartRateValue?.heartRate?.let { "$it bpm" } ?: "Waiting HR",
        heartRateStatusLabel(heartRateValue?.status),
        heartRateLiveLabel(liveHeartRateValue),
        dataState.lastHeartRateUpdateAtMillis?.let { "At ${formatClockTime(it)}" } ?: "No HR update"
    )

    val uploadLines = uploadSummaryLines(
        snapshot = lastUploadedSnapshot,
        emptyText = stringResource(R.string.null_value)
    )

    val uploadTargetLines = listOf(
        dataState.uploadHost,
        "Port ${dataState.uploadPort}",
        "HTTP POST /"
    )

    val serviceLines = serviceStatusLines(
        connectionState = connectionState,
        progressState = progressState,
        wearStatusSnapshot = wearStatusSnapshot
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LabeledStatusIndicator(
                    label = "HTS",
                    active = connectionState == ContinuousConnectionState.Connected
                )
                LabeledStatusIndicator(
                    label = "RUN",
                    active = progressState == ContinuousTrackingProgressState.Tracking
                )
                LabeledStatusIndicator(
                    label = "WEAR",
                    active = wearStatusSnapshot?.isWorn == true,
                    inactiveColor = Color(0xFFE39B9B),
                    unknown = wearStatusSnapshot == null
                )
            }

            ContinuousMetricBlock(
                title = "SERVICE",
                lines = serviceLines,
                cardHeight = 110.dp,
                modifier = Modifier.fillMaxWidth()
            )

            ContinuousMetricBlock(
                title = "EDA",
                lines = edaLines,
                cardHeight = 110.dp,
                modifier = Modifier.fillMaxWidth()
            )
            ContinuousMetricBlock(
                title = "HR",
                lines = heartRateLines,
                cardHeight = 130.dp,
                modifier = Modifier.fillMaxWidth()
            )
            ContinuousMetricBlock(
                title = "TEMP",
                lines = skinTempLines,
                cardHeight = 120.dp,
                modifier = Modifier.fillMaxWidth()
            )
            ContinuousMetricBlock(
                title = "UPLOAD",
                lines = uploadLines,
                cardHeight = 145.dp,
                modifier = Modifier.fillMaxWidth()
            )
            EditableMetricBlock(
                title = "TARGET",
                lines = uploadTargetLines,
                buttonLabel = stringResource(R.string.continuous_upload_target_edit),
                onEditClick = {
                    uploadHostInput = dataState.uploadHost
                    uploadPortInput = dataState.uploadPort.toString()
                    showUploadTargetDialog = true
                },
                cardHeight = 132.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun edaApplicationLines(
    label: EdaWindowLabel?,
    validSampleCount: Int
): List<String> {
    return when (label) {
        EdaWindowLabel.DETACHED -> listOf(
            "Wear Check",
            "Put the watch firmly on wrist",
            "Waiting for valid EDA samples"
        )

        EdaWindowLabel.LOW_SIGNAL -> listOf(
            "Contact Weak",
            "Tighten the watch and keep still",
            "Upload resumes on valid samples"
        )

        EdaWindowLabel.STABLE,
        EdaWindowLabel.RISING,
        EdaWindowLabel.RECOVERING,
        EdaWindowLabel.VARIABLE -> listOf(
            "Live Stream",
            "Valid $validSampleCount pts",
            "Upload each valid sample"
        )

        EdaWindowLabel.WAITING, null -> listOf(
            "Listening",
            "Valid $validSampleCount pts",
            "Upload each valid sample"
        )
    }
}

@Composable
private fun skinTempStatusLabel(status: SkinTempStatus?): String {
    return when (status) {
        SkinTempStatus.SUCCESSFUL_MEASUREMENT -> "Status SUCCESS"
        SkinTempStatus.INVALID_MEASUREMENT -> "Status INVALID"
        SkinTempStatus.UNKNOWN -> "Status UNKNOWN"
        null -> "Status WAITING"
    }
}

@Composable
private fun heartRateStatusLabel(status: Int?): String {
    return status?.let { "Status $it" } ?: "Status WAITING"
}

private fun heartRateLiveLabel(value: com.samsung.health.sensorsdksample.edatracking.data.HeartRateValue?): String {
    val heartRateText = value?.heartRate?.let { "$it bpm" } ?: "--"
    val statusText = value?.status?.toString() ?: "--"
    return "Live $heartRateText / S$statusText"
}

private fun uploadSummaryLines(
    snapshot: UploadedSnapshot?,
    emptyText: String
): List<String> {
    if (snapshot == null) {
        return listOf(
            "Last Send",
            emptyText,
            "Waiting for success",
            ""
        )
    }

    return listOf(
        "At ${formatClockTime(snapshot.uploadedAtMillis)}",
        snapshot.sensorType,
        snapshot.primaryText,
        snapshot.secondaryText.orEmpty()
    )
}

private fun formatClockTime(timeMillis: Long): String {
    val calendar = java.util.Calendar.getInstance().apply {
        timeInMillis = timeMillis
    }
    return String.format(
        Locale.getDefault(),
        "%02d:%02d:%02d",
        calendar.get(java.util.Calendar.HOUR_OF_DAY),
        calendar.get(java.util.Calendar.MINUTE),
        calendar.get(java.util.Calendar.SECOND)
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
            color = Color.Black,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ContinuousMetricBlock(
    title: String,
    lines: List<String>,
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(cardHeight),
        color = Color(0xFFF3F3F3),
        shape = RoundedCornerShape(20.dp)
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
    cardHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.height(cardHeight),
        color = Color(0xFFF3F3F3),
        shape = RoundedCornerShape(20.dp)
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
            androidx.compose.material3.TextButton(onClick = onEditClick) {
                androidx.compose.material3.Text(text = buttonLabel)
            }
        }
    }
}

@Composable
private fun ContinuousStatusIndicator(
    active: Boolean,
    inactiveColor: Color = Color(0xFFE5E5E5)
) {
    Surface(
        color = if (active) Color(0xFFB8E6C2) else inactiveColor,
        shape = CircleShape,
        modifier = Modifier.size(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
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

private fun serviceStatusLines(
    connectionState: ContinuousConnectionState,
    progressState: ContinuousTrackingProgressState,
    wearStatusSnapshot: WearStatusSnapshot?
): List<String> {
    val connectionText = when (connectionState) {
        ContinuousConnectionState.Connected -> "HTS Connected"
        ContinuousConnectionState.Disconnected -> "HTS Disconnected"
    }
    val progressText = when (progressState) {
        ContinuousTrackingProgressState.Tracking -> "Measuring"
        ContinuousTrackingProgressState.Idle -> "Waiting"
        ContinuousTrackingProgressState.TrackingDisabled -> "Disabled"
    }
    val wearText = when (wearStatusSnapshot?.isWorn) {
        true -> "Wear On"
        false -> "Wear Off"
        null -> "Wear Unknown"
    }
    val changedAtText = wearStatusSnapshot?.let { "Since ${formatClockTime(it.changedAtMillis)}" } ?: "Waiting sensor"

    return listOf(connectionText, progressText, wearText, changedAtText)
}