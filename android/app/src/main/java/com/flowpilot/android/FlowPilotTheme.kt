package com.flowpilot.android

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal object FlowPilotPalette {
    val Background = Color(0xFFF5F7FB)
    val Ink = Color(0xFF172033)
    val Muted = Color(0xFF8992A5)
    val Line = Color(0xFFECEFF4)
    val Purple = Color(0xFF6C5CE7)
    val PurpleDark = Color(0xFF5545D1)
    val PurpleSoft = Color(0xFFF0EDFF)
    val Green = Color(0xFF20A87A)
    val Red = Color(0xFFE46A72)
}

internal val FlowPilotColorScheme = lightColorScheme(
    primary = FlowPilotPalette.Purple,
    onPrimary = Color.White,
    primaryContainer = FlowPilotPalette.PurpleSoft,
    onPrimaryContainer = FlowPilotPalette.PurpleDark,
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
