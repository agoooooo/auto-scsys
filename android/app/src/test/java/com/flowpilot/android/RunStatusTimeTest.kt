package com.flowpilot.android

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class RunStatusTimeTest {
    @Test
    fun formatsRunStartTimeInTheSuppliedTimeZone() {
        assertEquals(
            "2026-10-11 06:25:27",
            formatRunStartTime(1_791_699_927_000L, ZoneId.of("UTC")),
        )
    }
}
