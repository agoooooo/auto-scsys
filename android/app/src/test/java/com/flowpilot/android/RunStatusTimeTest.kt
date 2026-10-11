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

    @Test
    fun formatsServerTimeInTheSuppliedTimeZone() {
        assertEquals(
            "2026-10-11 14:25:27",
            formatServerRunTime("2026-10-11T06:25:27+00:00", ZoneId.of("Asia/Shanghai")),
        )
    }

    @Test
    fun preservesUnparseableServerTimestampAndLabelsMissingTime() {
        assertEquals("unknown-time", formatServerRunTime("unknown-time", ZoneId.of("UTC")))
        assertEquals("时间未知", formatServerRunTime("", ZoneId.of("UTC")))
    }
}
