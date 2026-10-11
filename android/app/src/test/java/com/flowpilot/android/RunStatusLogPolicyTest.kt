package com.flowpilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStatusLogPolicyTest {
    @Test
    fun clearsLogWhenRunChanges() {
        assertTrue(shouldClearRunLog("run-1", "success", "run-2", "awaiting_permission"))
    }

    @Test
    fun clearsLogWhenSameRunStartsAgain() {
        assertTrue(shouldClearRunLog("run-1", "failure", "run-1", "running"))
    }

    @Test
    fun preservesLogForStatusUpdatesDuringOneRun() {
        assertFalse(shouldClearRunLog("run-1", "running", "run-1", "running"))
        assertFalse(shouldClearRunLog("run-1", "running", "run-1", "success"))
    }
}
