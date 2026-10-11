package com.flowpilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskActionPolicyTest {
    @Test
    fun scriptCanBeInspectedWithoutAccessibilityPermission() {
        assertTrue(canInspectTaskScript("active", null))
    }

    @Test
    fun scriptCannotBeInspectedForDisabledDevicesOrDuringRuns() {
        assertFalse(canInspectTaskScript("disabled", null))
        assertFalse(canInspectTaskScript("active", "running"))
        assertFalse(canInspectTaskScript("active", "awaiting_permission"))
    }

    @Test
    fun executionRequiresReviewConfirmationAndAccessibilityPermission() {
        assertTrue(canExecuteTaskScript(true, true, true))
        assertFalse(canExecuteTaskScript(false, true, true))
        assertFalse(canExecuteTaskScript(true, false, true))
        assertFalse(canExecuteTaskScript(true, true, false))
    }
}
