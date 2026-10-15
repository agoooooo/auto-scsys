package com.flowpilot.controller

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateInstallerTest {
    @Test
    fun onlyHigherVersionCodesAreEligibleForUpdate() {
        assertTrue(isNewerControllerAppRelease(4, 5))
        assertFalse(isNewerControllerAppRelease(5, 5))
        assertFalse(isNewerControllerAppRelease(6, 5))
    }

    @Test
    fun repeatedInstallPromptIsDeferredForSixHours() {
        assertTrue(
            isControllerAppUpdatePromptDeferred(
                7,
                10_000,
                7,
                10_000 + CONTROLLER_APP_UPDATE_PROMPT_COOLDOWN_MILLIS - 1,
            ),
        )
        assertFalse(
            isControllerAppUpdatePromptDeferred(
                7,
                10_000,
                7,
                10_000 + CONTROLLER_APP_UPDATE_PROMPT_COOLDOWN_MILLIS,
            ),
        )
        assertFalse(isControllerAppUpdatePromptDeferred(7, 10_000, 8, 10_001))
    }

    @Test
    fun cachedApkIsReusedOnlyWhenItsSha256Matches() {
        val content = "signed apk fixture".toByteArray()
        val apk = File.createTempFile("flowpilot-controller-update-", ".apk")
        try {
            apk.writeBytes(content)
            val sha256 = MessageDigest.getInstance("SHA-256").digest(content)
                .joinToString("") { "%02x".format(it) }
            assertTrue(isCachedControllerAppUpdateValid(apk, sha256))
            assertFalse(isCachedControllerAppUpdateValid(apk, "0".repeat(64)))
        } finally {
            apk.delete()
        }
    }
}
