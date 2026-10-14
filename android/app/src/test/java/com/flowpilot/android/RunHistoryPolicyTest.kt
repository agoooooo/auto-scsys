package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RunHistoryPolicyTest {
    @Test
    fun currentRunCardIsOnlyShownForActiveExecution() {
        assertEquals(true, isCurrentRunStatus("running"))
        assertEquals(true, isCurrentRunStatus("awaiting_permission"))
        assertEquals(false, isCurrentRunStatus("success"))
        assertEquals(false, isCurrentRunStatus(null))
    }

    @Test
    fun showsTheTenNewestRunsWithoutReordering() {
        val records = (1..12).map { index ->
            RunRecord(
                id = "run-$index",
                taskId = "task",
                taskName = "Task",
                version = "v1.0.$index",
                deviceId = "device",
                deviceName = "Device",
                remoteRunId = null,
                status = "success",
                startedAt = "2026-10-11T00:00:00+00:00",
                finishedAt = "2026-10-11T00:01:00+00:00",
                resultSummary = "done",
            )
        }

        assertEquals(
            (1..10).map { "run-$it" },
            visibleRunHistory(records).map(RunRecord::id),
        )
    }
}
