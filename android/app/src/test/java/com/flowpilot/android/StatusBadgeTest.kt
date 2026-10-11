package com.flowpilot.android

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusBadgeTest {
    @Test
    fun matchesDemoColorsForSuccessRunningAndOfflineStates() {
        assertEquals(Color(0xFFEAF8F2), runStatusBadgeStyle("success").background)
        assertEquals(FlowPilotPalette.PurpleSoft, runStatusBadgeStyle("running").background)
        assertEquals(Color(0xFFFFF6E9), runStatusBadgeStyle("offline").background)
    }

    @Test
    fun stoppedAndUnknownStatesUseNeutralColors() {
        assertEquals(runStatusBadgeStyle("stopped"), runStatusBadgeStyle("unknown"))
        assertEquals(runStatusBadgeStyle("failure"), runStatusBadgeStyle("stopped"))
    }
}
