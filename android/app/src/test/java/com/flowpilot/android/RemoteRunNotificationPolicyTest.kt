package com.flowpilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRunNotificationPolicyTest {
    @Test
    fun notifiesForUnacknowledgedPreauthorizedRun() {
        assertTrue(shouldNotifyPreauthorizedRun("running", "run-1", false))
    }

    @Test
    fun doesNotNotifyHandledRunsOrRequestsWithoutRunnableRunId() {
        assertFalse(shouldNotifyPreauthorizedRun("running", "run-1", true))
        assertFalse(shouldNotifyPreauthorizedRun("running", null, false))
        assertFalse(shouldNotifyPreauthorizedRun("running", "", false))
        assertFalse(shouldNotifyPreauthorizedRun("pending", "run-1", false))
        assertFalse(shouldNotifyPreauthorizedRun("success", "run-1", false))
    }
}
