package com.flowpilot.android

internal enum class AuthorizationSetupStep {
    NOTIFICATION,
    ACCESSIBILITY,
    SCREEN_CAPTURE,
    COMPLETE,
}

internal fun nextAuthorizationSetupStep(
    notificationGranted: Boolean,
    accessibilityEnabled: Boolean,
    screenCaptureActive: Boolean,
): AuthorizationSetupStep = when {
    !notificationGranted -> AuthorizationSetupStep.NOTIFICATION
    !accessibilityEnabled -> AuthorizationSetupStep.ACCESSIBILITY
    !screenCaptureActive -> AuthorizationSetupStep.SCREEN_CAPTURE
    else -> AuthorizationSetupStep.COMPLETE
}
