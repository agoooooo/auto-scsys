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
    fun remoteRequestsRunDirectlyWhenRequiredSystemCapabilitiesAreReady() {
        assertTrue(requiresScreenCapture(listOf("OCR 文字识别")))
        assertTrue(requiresScreenCapture(listOf("屏幕捕获（需系统授权）")))
        assertFalse(requiresScreenCapture(listOf("无障碍操作")))
        assertTrue(requiresAccessibility(listOf("无障碍点击")))
        assertFalse(requiresAccessibility(listOf("OCR 文字识别")))
        assertTrue(
            shouldDirectlyRunRemoteRequest(
                "pending",
                null,
                accessibilityRequired = false,
                accessibilityEnabled = false,
                deviceRunStatus = null,
            ),
        )
        assertTrue(
            shouldDirectlyRunRemoteRequest(
                "running",
                "run-1",
                accessibilityRequired = true,
                accessibilityEnabled = true,
                deviceRunStatus = null,
            ),
        )
        assertFalse(
            shouldDirectlyRunRemoteRequest(
                "running",
                "run-1",
                accessibilityRequired = true,
                accessibilityEnabled = false,
                deviceRunStatus = null,
            ),
        )
        assertFalse(
            shouldDirectlyRunRemoteRequest(
                "pending",
                null,
                accessibilityRequired = false,
                accessibilityEnabled = true,
                deviceRunStatus = "running",
            ),
        )
        assertFalse(
            shouldDirectlyRunRemoteRequest(
                "running",
                null,
                accessibilityRequired = false,
                accessibilityEnabled = true,
                deviceRunStatus = null,
            ),
        )
        assertFalse(
            shouldDirectlyRunRemoteRequest(
                "success",
                "run-1",
                accessibilityRequired = false,
                accessibilityEnabled = true,
                deviceRunStatus = null,
            ),
        )
        assertFalse(
            shouldDirectlyRunRemoteRequest(
                "pending",
                null,
                accessibilityRequired = false,
                accessibilityEnabled = true,
                deviceRunStatus = "awaiting_permission",
            ),
        )
    }

    @Test
    fun activeOcrStandbyReusesCaptureWithoutAnotherSystemPrompt() {
        val ocr = listOf("OCR 文字识别")
        assertTrue(requiresNewScreenCaptureAuthorization(ocr, captureSessionActive = false))
        assertFalse(requiresNewScreenCaptureAuthorization(ocr, captureSessionActive = true))
        assertFalse(
            requiresNewScreenCaptureAuthorization(
                listOf("无障碍操作"),
                captureSessionActive = false,
            ),
        )
    }

    @Test
    fun ocrStandbyCannotStartWhileTaskIsRunningOrAwaitingCapturePermission() {
        assertTrue(canStartOcrStandby(null))
        assertTrue(canStartOcrStandby("success"))
        assertFalse(canStartOcrStandby("running"))
        assertFalse(canStartOcrStandby("awaiting_permission"))
    }

    @Test
    fun permissionOnboardingAppearsOnceForSignedInUserAfterLoading() {
        assertTrue(shouldShowPermissionOnboarding("user-1", loading = false, alreadyShownForUser = false))
        assertFalse(shouldShowPermissionOnboarding("user-1", loading = true, alreadyShownForUser = false))
        assertFalse(shouldShowPermissionOnboarding("user-1", loading = false, alreadyShownForUser = true))
        assertFalse(shouldShowPermissionOnboarding(null, loading = false, alreadyShownForUser = false))
        assertFalse(shouldShowPermissionOnboarding("", loading = false, alreadyShownForUser = false))
    }

    @Test
    fun permissionOnboardingAcknowledgementIsScopedToServerAndUser() {
        val userOne = permissionOnboardingPreferenceKey("http://server-a", "user-1")
        assertEquals(userOne, permissionOnboardingPreferenceKey("http://server-a", "user-1"))
        assertTrue(userOne != permissionOnboardingPreferenceKey("http://server-b", "user-1"))
        assertTrue(userOne != permissionOnboardingPreferenceKey("http://server-a", "user-2"))
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
