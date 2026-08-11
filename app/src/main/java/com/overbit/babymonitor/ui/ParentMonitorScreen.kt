package com.overbit.babymonitor.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.overbit.babymonitor.ParentUnitViewModel
import com.overbit.babymonitor.R
import com.overbit.babymonitor.Role
import com.overbit.babymonitor.p2p.WifiDirectManager

/** The viewing end: finds the baby unit, joins its group, and plays what it sends. */
@Composable
fun ParentMonitorScreen(wifiDirect: WifiDirectManager, onBack: () -> Unit) {
    PermissionGate(role = Role.PARENT, onBack = onBack) {
        ParentMonitorContent(wifiDirect, onBack)
    }
}

@Composable
private fun ParentMonitorContent(wifiDirect: WifiDirectManager, onBack: () -> Unit) {
    val viewModel: ParentUnitViewModel = viewModel()
    val connection by wifiDirect.connectionInfo.collectAsStateWithLifecycle()

    val leave: () -> Unit = {
        viewModel.disconnect()
        wifiDirect.disconnect()
        onBack()
    }

    LaunchedEffect(connection) {
        val info = connection ?: return@LaunchedEffect
        val owner = info.groupOwnerAddress
        if (info.groupFormed && !info.isGroupOwner && owner != null) {
            viewModel.connect(owner)
        }
    }

    if (connection?.groupFormed == true && connection?.isGroupOwner == false) {
        Viewer(viewModel, leave)
    } else {
        PeerPicker(wifiDirect, connection?.isGroupOwner == true, onBack)
    }
}

@Composable
private fun PeerPicker(
    wifiDirect: WifiDirectManager,
    isGroupOwner: Boolean,
    onBack: () -> Unit,
) {
    val peers by wifiDirect.peers.collectAsStateWithLifecycle()
    val wifiEnabled by wifiDirect.wifiP2pEnabled.collectAsStateWithLifecycle()
    val message by wifiDirect.message.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { wifiDirect.discoverPeers() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.parent_unit_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                !wifiEnabled -> stringResource(R.string.wifi_off)
                isGroupOwner -> stringResource(R.string.parent_is_owner)
                peers.isEmpty() -> stringResource(R.string.searching)
                else -> stringResource(R.string.pick_baby_unit)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(peers, key = { it.deviceAddress }) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { wifiDirect.connect(device) }
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = device.deviceName.ifBlank { device.deviceAddress },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = device.deviceAddress,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        message?.let {
            Text(text = it, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = { wifiDirect.discoverPeers() },
            enabled = wifiEnabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.search_again))
        }
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.back))
        }
    }
}

@Composable
private fun Viewer(viewModel: ParentUnitViewModel, onLeave: () -> Unit) {
    val context = LocalContext.current
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val format by viewModel.videoFormat.collectAsStateWithLifecycle()
    val rendering by viewModel.rendering.collectAsStateWithLifecycle()
    val level by viewModel.level.collectAsStateWithLifecycle()
    val threshold by viewModel.alertThreshold.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val alert by viewModel.noiseAlert.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var dimmed by remember { mutableStateOf(false) }

    // Keep the picture up, and drop the backlight to near-zero in dim mode so the phone can sit
    // on a bedside table all night without lighting up the room.
    val window = (context as? Activity)?.window
    DisposableEffect(dimmed, window) {
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = if (dimmed) {
                    0.01f
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val currentFormat = format
        if (currentFormat != null) {
            VideoSurface(
                width = currentFormat.width,
                height = currentFormat.height,
                rotationDegrees = currentFormat.rotationDegrees,
                fixBufferSize = false,
                onSurfaceChanged = { viewModel.setSurface(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!rendering) {
            Text(
                text = stringResource(
                    if (connected) R.string.waiting_for_video else R.string.connecting
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        if (alert) {
            Text(
                text = stringResource(R.string.noise_alert),
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { viewModel.dismissAlert() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(12.dp),
        ) {
            error?.let {
                Text(text = it, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }

            LevelMeter(level = level, threshold = threshold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.alert_threshold),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = threshold,
                onValueChange = { viewModel.setAlertThreshold(it) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.toggleTorch() },
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.light))
                }
                OutlinedButton(
                    onClick = { viewModel.switchCamera() },
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.flip))
                }
                OutlinedButton(
                    onClick = { viewModel.toggleMute() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (muted) R.string.unmute else R.string.mute))
                }
                OutlinedButton(
                    onClick = { dimmed = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.dim))
                }
            }
            TextButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.disconnect))
            }
        }

        if (dimmed) {
            // Audio and the noise alert keep running underneath; a tap brings the picture back.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { dimmed = false },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.dimmed_hint),
                    color = Color(0xFF303030),
                )
            }
        }
    }
}

@Composable
private fun LevelMeter(level: Float, threshold: Float) {
    val animated by animateFloatAsState(targetValue = level, label = "level")
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(
                    if (level >= threshold) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
        )
    }
}
