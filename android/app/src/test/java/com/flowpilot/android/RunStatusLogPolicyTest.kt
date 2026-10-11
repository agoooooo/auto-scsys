package com.flowpilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStatusLogPolicyTest {
    @Test
    fun clearsLogWhenRunChanges() {
        assertTrue(shouldStartNewRun("run-1", "success", "run-2", "awaiting_permission"))
    }

    @Test
    fun clearsLogWhenSameRunIsRetried() {
        assertTrue(shouldStartNewRun("run-1", "failure", "run-1", "running"))
    }

    @Test
    fun preservesLogAndStartTimeThroughPermissionAndExecutionStates() {
        assertFalse(shouldStartNewRun("run-1", "running", "run-1", "running"))
        assertFalse(shouldStartNewRun("run-1", "awaiting_permission", "run-1", "running"))
        assertFalse(shouldStartNewRun("run-1", "running", "run-1", "success"))
    }

    @Test
    fun startsFreshWhenASeparatePermissionRequestBegins() {
        assertTrue(shouldStartNewRun("run-1", "success", "run-1", "awaiting_permission"))
    }
}
