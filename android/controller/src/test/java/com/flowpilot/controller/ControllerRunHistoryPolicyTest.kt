package com.flowpilot.controller

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ControllerRunHistoryPolicyTest {
    @Test
    fun visibleHistoryKeepsOnlyTheMostRecentTenRecordsInServerOrder() {
        val records = (12 downTo 1).map { index ->
            ControllerRunHistory(
                id = "run-$index",
                taskId = "task-$index",
                taskName = "Task $index",
                version = "v1.0",
                deviceId = "device",
                deviceName = "Device",
                remoteRunId = null,
                status = "success",
                startedAt = "2026-10-14T00:00:00+00:00",
                finishedAt = "2026-10-14T00:01:00+00:00",
                resultSummary = "",
            )
        }

        assertEquals(
            (12 downTo 3).map { "run-$it" },
            visibleControllerRunHistory(records).map(ControllerRunHistory::id),
        )
    }

    @Test
    fun serverTimesAreFormattedInTheSelectedLocalTimeZone() {
        assertEquals(
            "2026-10-14 13:39:42",
            formatControllerRunTime(
                "2026-10-14T05:39:42+00:00",
                ZoneId.of("Asia/Shanghai"),
            ),
        )
        assertEquals("时间未知", formatControllerRunTime(""))
        assertEquals("not-a-timestamp", formatControllerRunTime("not-a-timestamp"))
    }

    @Test
    fun failedHistoryRunsUseErrorColorAndStoppedRunsUseNeutralColor() {
        assertEquals(
            ControllerColorScheme.error,
            controllerRunHistoryStatusColor("failure"),
        )
        assertEquals(
            ControllerColorScheme.onSurfaceVariant,
            controllerRunHistoryStatusColor("stopped"),
        )
        assertEquals(
            ControllerColorScheme.primary,
            controllerRunHistoryStatusColor("success"),
        )
    }

    @Test
    fun completedRunsAreHistoryOnlyAndActiveRequestsRemainVisible() {
        assertEquals(false, controllerRunHistoryIsActive("success"))
        assertEquals(false, controllerRunHistoryIsActive("failure"))
        assertEquals(true, controllerRunHistoryIsActive("running"))
        assertEquals(true, controllerRunHistoryIsActive("pending"))
    }
}
