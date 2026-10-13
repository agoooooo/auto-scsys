package com.flowpilot.android

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal object FlowPilotPalette {
    val Background = Color(0xFFF5F7FB)
    val Ink = Color(0xFF172033)
    val Muted = Color(0xFF8992A5)
    val Line = Color(0xFFECEFF4)
    val Accent = Color(0xFF07C160)
    val AccentDark = Color(0xFF079B4F)
    val AccentSoft = Color(0xFFE1F5E9)
    val Green = Color(0xFF20A87A)
    val Red = Color(0xFFE46A72)
}

internal val FlowPilotColorScheme = lightColorScheme(
    primary = FlowPilotPalette.Accent,
    onPrimary = Color.White,
    primaryContainer = FlowPilotPalette.AccentSoft,
    onPrimaryContainer = FlowPilotPalette.AccentDark,
    secondary = FlowPilotPalette.Green,
    onSecondary = Color.White,
    background = FlowPilotPalette.Background,
    onBackground = FlowPilotPalette.Ink,
    surface = Color.White,
    onSurface = FlowPilotPalette.Ink,
    surfaceVariant = Color(0xFFF1F2F6),
    onSurfaceVariant = FlowPilotPalette.Muted,
    outline = FlowPilotPalette.Line,
    error = Color(0xFFB85A60),
    errorContainer = Color(0xFFFFF0F0),
)
