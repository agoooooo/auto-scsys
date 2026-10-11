package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskActionPolicyTest {
    @Test
    fun douyinPackagePolicySupportsOnlyMainAndLiteApps() {
        assertTrue(DouyinPackagePolicy.isSupportedPackage(DouyinPackagePolicy.MAIN_PACKAGE))
        assertTrue(DouyinPackagePolicy.isSupportedPackage(DouyinPackagePolicy.LITE_PACKAGE))
        assertFalse(DouyinPackagePolicy.isSupportedPackage("com.ss.android.ugc.aweme.other"))
        assertFalse(DouyinPackagePolicy.isSupportedPackage(null))
    }

    @Test
    fun douyinPackagePolicyPrefersMainAppThenFallsBackToLite() {
        assertEquals(
            DouyinPackagePolicy.MAIN_PACKAGE,
            DouyinPackagePolicy.preferredInstalledPackage { it == DouyinPackagePolicy.MAIN_PACKAGE },
        )
        assertEquals(
            DouyinPackagePolicy.LITE_PACKAGE,
            DouyinPackagePolicy.preferredInstalledPackage { it == DouyinPackagePolicy.LITE_PACKAGE },
        )
        assertNull(DouyinPackagePolicy.preferredInstalledPackage { false })
    }

    @Test
    fun douyinForegroundWaitPollsUntilSupportedAppIsActive() {
        var elapsedMillis = 0L
        var checks = 0
        val active = awaitDouyinForeground(
            isForeground = { ++checks == 3 },
            timeoutMillis = 1_000,
            pollIntervalMillis = 250,
            wait = { elapsedMillis += it },
            nowNanos = { elapsedMillis * 1_000_000 },
        )

        assertTrue(active)
        assertEquals(3, checks)
        assertEquals(500L, elapsedMillis)
    }

    @Test
    fun douyinForegroundWaitStopsAtTimeout() {
        var elapsedMillis = 0L
        val active = awaitDouyinForeground(
            isForeground = { false },
            timeoutMillis = 600,
            pollIntervalMillis = 250,
            wait = { elapsedMillis += it },
            nowNanos = { elapsedMillis * 1_000_000 },
        )

        assertFalse(active)
        assertEquals(600L, elapsedMillis)
    }

    @Test
    fun douyinForegroundWaitStopsPromptlyWhenCancelled() {
        var elapsedMillis = 0L
        var cancelled = false
        val active = awaitDouyinForeground(
            isForeground = { false },
            timeoutMillis = 10_000,
            pollIntervalMillis = 250,
            isCancelled = { cancelled },
            wait = {
                elapsedMillis += it
                cancelled = true
            },
            nowNanos = { elapsedMillis * 1_000_000 },
        )

        assertFalse(active)
        assertEquals(250L, elapsedMillis)
    }

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

    @Test
    fun logoutBecomesAvailableAfterPermissionIsDeniedOrRunFinishes() {
        assertFalse(canLogoutDuringRun("running"))
        assertFalse(canLogoutDuringRun("awaiting_permission"))
        assertTrue(canLogoutDuringRun("failure"))
        assertTrue(canLogoutDuringRun("success"))
        assertTrue(canLogoutDuringRun("stopped"))
        assertTrue(canLogoutDuringRun(null))
    }
}
