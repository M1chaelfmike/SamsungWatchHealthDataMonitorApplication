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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.R
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempConnectionState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempDataState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempMessageState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempProgressState
import com.samsung.health.sensorsdksample.edatracking.data.SkinTempStatus
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.PaddingMedium
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.SpacerMedium
import com.samsung.health.sensorsdksample.edatracking.viewModel.SkinTempViewModel
import java.util.Locale

@Composable
fun SkinTempPage(
    viewModel: SkinTempViewModel,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val dataState by viewModel.dataState.collectAsState()
    val progressState by viewModel.progressState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    var showSettingsPrompt by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startTracking()
        } else if (activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, skinTempPermission())) {
            showSettingsPrompt = true
        } else {
            Toast.makeText(context, context.getString(R.string.skin_temp_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.messageState.collect { messageState ->
            when (messageState) {
                is SkinTempMessageState.TrackerNotAvailable -> {
                    Toast.makeText(context, context.getString(R.string.skin_temp_not_available_message), Toast.LENGTH_SHORT).show()
                }

                is SkinTempMessageState.ResolvableError -> {
                    activity?.let { messageState.exception.resolve(it) }
                }

                is SkinTempMessageState.Error -> {
                    Toast.makeText(
                        context,
                        messageState.errorMessage ?: context.getString(R.string.skin_temp_connection_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                is SkinTempMessageState.PermissionError -> {
                    Toast.makeText(context, context.getString(R.string.skin_temp_permission_required), Toast.LENGTH_SHORT).show()
                }

                is SkinTempMessageState.SdkPolicyError -> {
                    Toast.makeText(context, context.getString(R.string.skin_temp_sdk_policy_error_message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(connectionState, isActive) {
        if (!isActive) {
            return@LaunchedEffect
        }

        when (connectionState) {
            is SkinTempConnectionState.Connected -> {
                Toast.makeText(context, context.getString(R.string.connected_to_hts), Toast.LENGTH_SHORT).show()
            }

            is SkinTempConnectionState.Disconnected -> Unit
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
                androidx.compose.material3.Text(text = stringResource(R.string.skin_temp_permission_title))
            },
            text = {
                androidx.compose.material3.Text(text = stringResource(R.string.skin_temp_permission_required))
            }
        )
    }

    val headerText = when {
        progressState == SkinTempProgressState.Measuring -> stringResource(R.string.skin_temp_header_measuring)
        progressState == SkinTempProgressState.TrackingDisabled -> stringResource(R.string.skin_temp_not_available_message)
        connectionState == SkinTempConnectionState.Disconnected -> stringResource(R.string.skin_temp_no_connection_message)
        dataState is SkinTempDataState.DataObtained -> {
            val value = (dataState as SkinTempDataState.DataObtained).value
            if (value.status == SkinTempStatus.INVALID_MEASUREMENT) {
                stringResource(R.string.skin_temp_invalid_measurement)
            } else {
                stringResource(R.string.skin_temp_header_done)
            }
        }

        else -> stringResource(R.string.skin_temp_header_default)
    }

    val value = (dataState as? SkinTempDataState.DataObtained)?.value
    val ambientTemperature = formatTemperature(value?.ambientTemperature)
    val wristTemperature = formatTemperature(value?.wristSkinTemperature)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(PaddingMedium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.skin_temp_title),
                style = AppTypography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Text(
                text = headerText,
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkinTempValueColumn(
                    title = stringResource(R.string.skin_temp_wrist_label),
                    value = wristTemperature
                )
                SkinTempValueColumn(
                    title = stringResource(R.string.skin_temp_ambient_label),
                    value = ambientTemperature
                )
            }

            if (progressState == SkinTempProgressState.Measuring) {
                CircularProgressIndicator(modifier = Modifier.height(36.dp))
            }

            FilledTonalButton(
                onClick = {
                    if (progressState == SkinTempProgressState.Measuring) {
                        return@FilledTonalButton
                    }
                    if (connectionState == SkinTempConnectionState.Disconnected) {
                        Toast.makeText(context, context.getString(R.string.skin_temp_no_connection_message), Toast.LENGTH_SHORT).show()
                        return@FilledTonalButton
                    }
                    if (progressState == SkinTempProgressState.TrackingDisabled) {
                        Toast.makeText(context, context.getString(R.string.skin_temp_not_available_message), Toast.LENGTH_SHORT).show()
                        return@FilledTonalButton
                    }
                    if (hasSkinTempPermission(context)) {
                        viewModel.startTracking()
                    } else {
                        permissionLauncher.launch(skinTempPermission())
                    }
                }
            ) {
                Text(
                    text = if (progressState == SkinTempProgressState.Measuring) {
                        stringResource(R.string.skin_temp_button_measuring)
                    } else {
                        stringResource(R.string.skin_temp_button_default)
                    },
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun SkinTempValueColumn(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            style = AppTypography.bodySmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(SpacerMedium))
        Text(
            text = value,
            style = AppTypography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

private fun skinTempPermission(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
        HealthPermissions.READ_SKIN_TEMPERATURE
    } else {
        Manifest.permission.BODY_SENSORS
    }
}

private fun hasSkinTempPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, skinTempPermission()) == PackageManager.PERMISSION_GRANTED
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    intent.data = Uri.fromParts("package", context.packageName, null)
    context.startActivity(intent)
}

private fun formatTemperature(value: Float?): String {
    return if (value == null) {
        "--"
    } else {
        String.format(Locale.getDefault(), "%.1f°", value)
    }
}