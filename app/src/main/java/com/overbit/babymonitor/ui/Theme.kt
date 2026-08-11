package com.overbit.babymonitor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always dark: both units live in a dark bedroom, and a white screen at 3am is a mistake.
private val MonitorColors = darkColorScheme(
    primary = Color(0xFF7FC4E8),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFFB4CAD6),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E3E6),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E3E6),
    surfaceVariant = Color(0xFF1E262C),
    onSurfaceVariant = Color(0xFFBFC8CE),
    error = Color(0xFFFFB4AB),
)

@Composable
fun BabyMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MonitorColors, content = content)
}
