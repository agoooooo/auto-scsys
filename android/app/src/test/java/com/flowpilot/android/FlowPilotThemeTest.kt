package com.flowpilot.android

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowPilotThemeTest {
    @Test
    fun paletteMatchesTheWebDemoTheme() {
        assertEquals(Color(0xFFF5F7FB), FlowPilotColorScheme.background)
        assertEquals(Color(0xFF6C5CE7), FlowPilotColorScheme.primary)
        assertEquals(Color(0xFF172033), FlowPilotColorScheme.onSurface)
        assertEquals(Color.White, FlowPilotColorScheme.surface)
    }
}
