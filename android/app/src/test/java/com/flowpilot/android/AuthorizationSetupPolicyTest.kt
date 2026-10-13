package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthorizationSetupPolicyTest {
    @Test
    fun setupRequestsMissingPermissionsInSystemRequiredOrder() {
        assertEquals(
            AuthorizationSetupStep.NOTIFICATION,
            nextAuthorizationSetupStep(
                notificationGranted = false,
                accessibilityEnabled = false,
                screenCaptureActive = false,
            ),
        )
        assertEquals(
            AuthorizationSetupStep.ACCESSIBILITY,
            nextAuthorizationSetupStep(
                notificationGranted = true,
                accessibilityEnabled = false,
                screenCaptureActive = false,
            ),
        )
        assertEquals(
            AuthorizationSetupStep.SCREEN_CAPTURE,
            nextAuthorizationSetupStep(
                notificationGranted = true,
                accessibilityEnabled = true,
                screenCaptureActive = false,
            ),
        )
    }

    @Test
    fun setupSkipsAlreadyGrantedPermissions() {
        assertEquals(
            AuthorizationSetupStep.SCREEN_CAPTURE,
            nextAuthorizationSetupStep(
                notificationGranted = true,
                accessibilityEnabled = true,
                screenCaptureActive = false,
            ),
        )
        assertEquals(
            AuthorizationSetupStep.COMPLETE,
            nextAuthorizationSetupStep(
                notificationGranted = true,
                accessibilityEnabled = true,
                screenCaptureActive = true,
            ),
        )
    }
}
