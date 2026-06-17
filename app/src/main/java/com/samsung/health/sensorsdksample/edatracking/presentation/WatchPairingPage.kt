package com.samsung.health.sensorsdksample.edatracking.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Text
import com.samsung.health.sensorsdksample.edatracking.pairing.WatchPairingReason
import com.samsung.health.sensorsdksample.edatracking.pairing.WatchPairingState
import com.samsung.health.sensorsdksample.edatracking.presentation.theme.AppTypography

@Composable
fun WatchPairingPage(
    pairingState: WatchPairingState,
    onSkip: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusText = when (pairingState.reason) {
        WatchPairingReason.NETWORK_CHANGED -> "Network changed"
        WatchPairingReason.RECEIVER_ERROR -> "Receiver error"
        WatchPairingReason.PAIRED -> "Configuration ready"
        WatchPairingReason.FIRST_LAUNCH,
        WatchPairingReason.WAITING_FOR_CONFIG -> "Waiting for pairing"
    }
    val rows = listOf(
        "IP" to (pairingState.watchIp ?: "Unavailable"),
        "MAC" to (pairingState.macAddress ?: "Unavailable"),
        "PORT" to pairingState.receiverPort.toString(),
        "CODE" to pairingState.pairingCode,
        "TARGET" to "${pairingState.configuration.serverHost}:${pairingState.configuration.serverPort}",
        "WATCH" to pairingState.configuration.watchId
    )

    ScalingLazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
    ) {
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "PAIR WATCH",
                    style = AppTypography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusText,
                    style = AppTypography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = Color(0xFFB8C7D9)
                )
            }
        }

        items(rows) { row ->
            PairingInfoBlock(
                label = row.first,
                value = row.second,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Text(
                text = pairingState.message ?: "Open dashboard and send server settings.",
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color(0xFFB8C7D9),
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        item {
            PairingActionButton(
                label = "REFRESH",
                enabled = true,
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (pairingState.canSkip) {
            item {
                PairingActionButton(
                    label = "SKIP",
                    enabled = true,
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PairingInfoBlock(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF101820), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF26394A), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color(0xFF7EA6C8)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = AppTypography.bodySmall,
                textAlign = TextAlign.Center,
                color = Color.White
            )
        }
    }
}

@Composable
private fun PairingActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(999.dp)
    val backgroundColor = if (enabled) Color(0xFF1A73E8) else Color(0xFF3D4650)
    Box(
        modifier = modifier
            .height(40.dp)
            .background(backgroundColor, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = AppTypography.bodySmall,
            textAlign = TextAlign.Center,
            color = Color.White
        )
    }
}
