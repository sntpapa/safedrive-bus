package com.safedrive.bus.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD4FF),
    onPrimary = Color(0xFF00344A),
    background = Color(0xFF101315),
    onBackground = Color(0xFFE6E9EB),
    surface = Color(0xFF181C1F),
    onSurface = Color(0xFFE6E9EB),
    surfaceVariant = Color(0xFF232A2E),
    onSurfaceVariant = Color(0xFFB9C3C8),
    error = Color(0xFFFF8A80)
)

private val Light = lightColorScheme(
    primary = Color(0xFF00668A),
    background = Color(0xFFF7F9FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6ECEF),
    error = Color(0xFFB3261E)
)

/** 운전 중 야간 시인성을 위해 다크를 기본으로 둔다. */
@Composable
fun SafeDriveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}

val PassGreen = Color(0xFF4CD07D)
val BlockRed = Color(0xFFFF6B6B)
val WarnAmber = Color(0xFFFFC46B)
