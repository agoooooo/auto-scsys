package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RunHistoryPolicyTest {
    @Test
    fun showsTheTenNewestRunsWithoutReordering() {
        val records = (1..12).map { index ->
            RunRecord(
                id = "run-$index",
                taskId = "task",
                taskName = "Task",
                version = "v1.0.$index",
                deviceId = "device",
                status = "success",
                startedAt = "2026-10-11T00:00:00+00:00",
                resultSummary = "done",
            )
        }

        assertEquals(
            (1..10).map { "run-$it" },
            visibleRunHistory(records).map(RunRecord::id),
        )
    }
}
