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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingMessageState
import com.samsung.health.sensorsdksample.edatracking.data.ContinuousTrackingProgressState
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.PaddingMedium
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.SpacerMedium
import com.samsung.health.sensorsdksample.edatracking.viewModel.ContinuousTrackingViewModel
import java.util.Locale

@Composable
fun ContinuousMonitoringPage(
    viewModel: ContinuousTrackingViewModel,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val dataState by viewModel.dataState.collectAsState()
    val progressState by viewModel.progressState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    var showSettingsPrompt by remember { mutableStateOf(false) }
    val missingPermissionText = remember(context, connectionState, progressState, isActive) {
        buildMissingPermissionsText(context)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = requiredContinuousPermissions().all { permission -> results[permission] == true }
        if (granted) {
            if (isActive && connectionState == ContinuousConnectionState.Connected) {
                viewModel.startTracking()
            }
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

                is ContinuousTrackingMessageState.TrackingInUse -> {
                    Toast.makeText(context, context.getString(R.string.continuous_eda_in_use), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            viewModel.connect()
        } else {
            viewModel.stopTracking()
            viewModel.disconnect()
        }
    }

    LaunchedEffect(isActive, connectionState, progressState) {
        if (!isActive) {
            return@LaunchedEffect
        }
        if (connectionState != ContinuousConnectionState.Connected) {
            return@LaunchedEffect
        }
        if (progressState == ContinuousTrackingProgressState.Tracking || progressState == ContinuousTrackingProgressState.TrackingDisabled) {
            return@LaunchedEffect
        }

        if (hasContinuousPermissions(context)) {
            viewModel.startTracking()
        } else {
            permissionLauncher.launch(requiredContinuousPermissions())
        }
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.stopTracking()
            viewModel.disconnect()
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

    val edaValue = dataState.edaValue
    val skinTempValue = dataState.skinTempValue
    val heartRateValue = dataState.heartRateValue
    val ppgValue = dataState.ppgValue

    val edaLines = listOf(
        edaValue?.skinConductance?.let { String.format(Locale.getDefault(), "%.3f µS", it) }
            ?: stringResource(R.string.null_value)
    )

    val skinTempLines = listOf(
        skinTempValue?.wristSkinTemperature?.let { String.format(Locale.getDefault(), "WS %.1f°C", it) }
            ?: "WS ${stringResource(R.string.null_value)}",
        skinTempValue?.ambientTemperature?.let { String.format(Locale.getDefault(), "AT %.1f°C", it) }
            ?: "AT ${stringResource(R.string.null_value)}"
    )

    val heartRateLines = listOf(
        heartRateValue?.heartRate?.let { "$it bpm" }
            ?: "-- bpm"
    )

    val ppgLines = listOf(
        ppgValue?.green?.let { "G $it" } ?: "G --",
        ppgValue?.red?.let { "R $it" } ?: "R --",
        ppgValue?.ir?.let { "IR $it" } ?: "IR --"
    )

    BoxWithConstraints(
        modifier = modifier.fillMaxSize()
    ) {
        val outerPadding = 10.dp
        val cardSpacing = 8.dp
        val rowSpacing = 8.dp
        val contentWidth = maxWidth - outerPadding * 2
        val contentHeight = maxHeight - outerPadding * 2
        val statusSectionHeight = 18.dp
        val cardWidth = (contentWidth - cardSpacing) / 2
        val computedCardHeight = (contentHeight - statusSectionHeight - rowSpacing * 2) / 2
        val cardHeight = if (computedCardHeight > 92.dp) 92.dp else computedCardHeight

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(outerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(rowSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContinuousStatusIndicator(active = connectionState == ContinuousConnectionState.Connected)
                    Spacer(modifier = Modifier.size(8.dp))
                    ContinuousStatusIndicator(active = progressState == ContinuousTrackingProgressState.Tracking)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(cardSpacing)
                ) {
                    ContinuousMetricBlock(
                        title = "EDA",
                        lines = edaLines,
                        cardHeight = cardHeight,
                        modifier = Modifier.width(cardWidth)
                    )
                    ContinuousMetricBlock(
                        title = "ST",
                        lines = skinTempLines,
                        cardHeight = cardHeight,
                        modifier = Modifier.width(cardWidth)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(cardSpacing)
                ) {
                    ContinuousMetricBlock(
                        title = "HR",
                        lines = heartRateLines,
                        cardHeight = cardHeight,
                        modifier = Modifier.width(cardWidth)
                    )
                    ContinuousMetricBlock(
                        title = "PPG",
                        lines = ppgLines,
                        cardHeight = cardHeight,
                        modifier = Modifier.width(cardWidth)
                    )
                }
            }
        }
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
                .padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = AppTypography.bodySmall.copy(fontSize = 11.sp),
                textAlign = TextAlign.Center,
                color = Color.Black
            )
            Spacer(modifier = Modifier.height(4.dp))
            lines.forEachIndexed { index, line ->
                Text(
                    text = line,
                    style = AppTypography.bodySmall.copy(fontSize = 10.sp),
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
private fun ContinuousStatusIndicator(
    active: Boolean
) {
    Surface(
        color = if (active) Color(0xFFB8E6C2) else Color(0xFFE5E5E5),
        shape = CircleShape,
        modifier = Modifier.size(10.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize())
    }
}

private fun requiredContinuousPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        arrayOf(
            "com.samsung.android.hardware.sensormanager.permission.READ_ADDITIONAL_HEALTH_DATA",
            HealthPermissions.READ_SKIN_TEMPERATURE,
            HealthPermissions.READ_HEART_RATE
        )
    } else {
        arrayOf(Manifest.permission.BODY_SENSORS)
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
    if (!ppgSupported) {
        missingSensors += "PPG"
    }

    return context.getString(R.string.continuous_missing_sensors, missingSensors.joinToString(", "))
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.fromParts("package", context.packageName, null)
    context.startActivity(intent)
}