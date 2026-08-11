package com.overbit.babymonitor

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.overbit.babymonitor.p2p.WifiDirectManager
import com.overbit.babymonitor.ui.BabyMonitorScreen
import com.overbit.babymonitor.ui.BabyMonitorTheme
import com.overbit.babymonitor.ui.ParentMonitorScreen

enum class Role { BABY, PARENT }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BabyMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MonitorApp()
                }
            }
        }
    }
}

@Composable
private fun MonitorApp() {
    val context = LocalContext.current
    val wifiDirect = remember { WifiDirectManager(context.applicationContext) }
    var role by rememberSaveable { mutableStateOf<Role?>(null) }

    DisposableEffect(wifiDirect) {
        wifiDirect.register()
        onDispose { wifiDirect.unregister() }
    }

    when (role) {
        null -> RoleScreen(
            wifiDirectSupported = wifiDirect.isSupported,
            onPick = { role = it },
        )

        Role.BABY -> BabyMonitorScreen(
            wifiDirect = wifiDirect,
            onBack = { role = null },
        )

        Role.PARENT -> ParentMonitorScreen(
            wifiDirect = wifiDirect,
            onBack = { role = null },
        )
    }
}

@Composable
private fun RoleScreen(wifiDirectSupported: Boolean, onPick: (Role) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.role_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.role_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = { onPick(Role.BABY) },
            enabled = wifiDirectSupported,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.role_baby))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { onPick(Role.PARENT) },
            enabled = wifiDirectSupported,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.role_parent))
        }
        if (!wifiDirectSupported) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_wifi_direct),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Runtime permissions each role needs. POST_NOTIFICATIONS is requested but not required: the
 * foreground service still runs without it, the user just won't see the ongoing notification.
 */
fun permissionsFor(role: Role): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        // Wi-Fi Direct discovery counts as a location capability below Android 13.
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (role == Role.BABY) {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
