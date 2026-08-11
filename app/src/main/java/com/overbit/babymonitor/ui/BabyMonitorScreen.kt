package com.overbit.babymonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.overbit.babymonitor.BabyUnitService
import com.overbit.babymonitor.R
import com.overbit.babymonitor.Role
import com.overbit.babymonitor.p2p.WifiDirectManager

/**
 * The camera end. Creates the Wi-Fi Direct group, then runs the streaming service; the screen
 * itself is optional once streaming has started.
 */
@Composable
fun BabyMonitorScreen(wifiDirect: WifiDirectManager, onBack: () -> Unit) {
    PermissionGate(role = Role.BABY, onBack = onBack) {
        BabyMonitorContent(wifiDirect, onBack)
    }
}

@Composable
private fun BabyMonitorContent(wifiDirect: WifiDirectManager, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by BabyUnitService.state.collectAsStateWithLifecycle()
    val connection by wifiDirect.connectionInfo.collectAsStateWithLifecycle()
    val deviceName by wifiDirect.thisDeviceName.collectAsStateWithLifecycle()
    val wifiEnabled by wifiDirect.wifiP2pEnabled.collectAsStateWithLifecycle()
    val message by wifiDirect.message.collectAsStateWithLifecycle()

    val hosting = connection?.isGroupOwner == true

    // Release the camera's preview target when this screen goes away; the encoder target and
    // therefore the stream itself keep running inside the service.
    DisposableEffect(Unit) {
        onDispose { BabyUnitService.attachPreview(null) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.baby_unit_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                !wifiEnabled -> stringResource(R.string.wifi_off)
                !hosting -> stringResource(R.string.baby_unit_hint)
                state.parentConnected -> stringResource(R.string.baby_unit_parent_connected)
                else -> stringResource(R.string.baby_unit_waiting)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        deviceName?.let {
            Text(
                text = stringResource(R.string.baby_unit_device_name, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val size = state.videoSize
            if (state.streaming && size != null) {
                VideoSurface(
                    width = size.width,
                    height = size.height,
                    rotationDegrees = state.rotationDegrees,
                    fixBufferSize = true,
                    onSurfaceChanged = { BabyUnitService.attachPreview(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = stringResource(
                        if (state.streaming) R.string.starting_camera else R.string.preview_idle
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        state.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
        message?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(12.dp))

        if (state.streaming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { BabyUnitService.toggleTorch() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            if (state.torchOn) R.string.light_off else R.string.light_on
                        )
                    )
                }
                OutlinedButton(
                    onClick = { BabyUnitService.switchCamera() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.switch_camera))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    BabyUnitService.stop(context)
                    wifiDirect.disconnect()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.stop_monitoring))
            }
        } else {
            Button(
                onClick = {
                    wifiDirect.createGroup()
                    BabyUnitService.start(context)
                },
                enabled = wifiEnabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.start_monitoring))
            }
        }

        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}
