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
    fun taskCanBeStartedWhenDeviceIsActiveAndHasPublishedVersion() {
        assertTrue(canStartTask("active", null, "v1.0.0"))
    }

    @Test
    fun taskCannotBeStartedForDisabledDevicesMissingVersionsOrDuringRuns() {
        assertFalse(canStartTask("disabled", null, "v1.0.0"))
        assertFalse(canStartTask("active", null, ""))
        assertFalse(canStartTask("active", "running", "v1.0.0"))
        assertFalse(canStartTask("active", "awaiting_permission", "v1.0.0"))
    }

    @Test
    fun devicePermissionRowsShowOnlyStateAndOpenActionWhenDisabled() {
        assertEquals("开启", devicePermissionStatus(enabled = true))
        assertEquals("未开启", devicePermissionStatus(enabled = false))
        assertFalse(shouldShowPermissionOpenAction(enabled = true))
        assertTrue(shouldShowPermissionOpenAction(enabled = false))
    }

    @Test
    fun remoteStopPollingOnlyAppliesToActiveRemoteRuns() {
        assertTrue(shouldPollRemoteStop("remote-request", stopRequested = false))
        assertFalse(shouldPollRemoteStop(null, stopRequested = false))
        assertFalse(shouldPollRemoteStop("", stopRequested = false))
        assertFalse(shouldPollRemoteStop("remote-request", stopRequested = true))
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
    fun defaultStandbyStartsOnceAfterSignedInDeviceIsReady() {
        assertNull(defaultStandbyRequestKey("user-1", "device-1", loading = true, requestedFor = null))
        assertNull(defaultStandbyRequestKey(null, "device-1", loading = false, requestedFor = null))
        assertNull(defaultStandbyRequestKey("user-1", null, loading = false, requestedFor = null))
        assertEquals(
            "user-1:device-1",
            defaultStandbyRequestKey("user-1", "device-1", loading = false, requestedFor = null),
        )
        assertNull(
            defaultStandbyRequestKey(
                "user-1",
                "device-1",
                loading = false,
                requestedFor = "user-1:device-1",
            ),
        )
        assertEquals(
            "user-1:device-2",
            defaultStandbyRequestKey(
                "user-1",
                "device-2",
                loading = false,
                requestedFor = "user-1:device-1",
            ),
        )
    }

    @Test
    fun batteryOptimizationExemptionIsRequestedOnlyWhenNeededAndSupported() {
        assertTrue(shouldRequestBatteryOptimizationExemption(exemptionSupported = true, alreadyExempt = false))
        assertFalse(shouldRequestBatteryOptimizationExemption(exemptionSupported = true, alreadyExempt = true))
        assertFalse(shouldRequestBatteryOptimizationExemption(exemptionSupported = false, alreadyExempt = false))
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
