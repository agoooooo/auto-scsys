package com.flowpilot.android

internal fun canInspectTaskScript(deviceStatus: String, runStatus: String?): Boolean =
    deviceStatus != "disabled" && runStatus !in setOf("running", "awaiting_permission")

internal fun canLogoutDuringRun(runStatus: String?): Boolean =
    runStatus !in setOf("running", "awaiting_permission")

internal fun canExecuteTaskScript(
    scriptReviewed: Boolean,
    confirmationChecked: Boolean,
    accessibilityEnabled: Boolean,
): Boolean = scriptReviewed && confirmationChecked && accessibilityEnabled

internal fun requiresScreenCapture(capabilities: List<String>): Boolean =
    capabilities.any { capability ->
        capability.contains("屏幕", ignoreCase = true) ||
            capability.contains("screen", ignoreCase = true) ||
            capability.contains("OCR", ignoreCase = true) ||
            capability.contains("文字识别", ignoreCase = true) ||
            capability.contains("界面识别", ignoreCase = true) ||
            capability.contains("读屏", ignoreCase = true)
    }

internal fun requiresNewScreenCaptureAuthorization(
    capabilities: List<String>,
    captureSessionActive: Boolean,
): Boolean = requiresScreenCapture(capabilities) && !captureSessionActive

internal fun canStartOcrStandby(runStatus: String?): Boolean =
    runStatus !in setOf("running", "awaiting_permission")

internal fun shouldDirectlyRunRemoteRequest(
    status: String,
    runId: String?,
    accessibilityRequired: Boolean,
    accessibilityEnabled: Boolean,
    deviceRunStatus: String?,
): Boolean = (status == "pending" || (status == "running" && !runId.isNullOrBlank())) &&
    (!accessibilityRequired || accessibilityEnabled) &&
    canStartOcrStandby(deviceRunStatus)

internal fun shouldShowPermissionOnboarding(
    userId: String?,
    loading: Boolean,
    alreadyShownForUser: Boolean,
): Boolean = !userId.isNullOrBlank() && !loading && !alreadyShownForUser

internal fun requiresAccessibility(capabilities: List<String>): Boolean =
    capabilities.any { capability ->
        capability.contains("无障碍", ignoreCase = true) ||
            capability.contains("accessibility", ignoreCase = true) ||
            capability.contains("点击", ignoreCase = true)
    }
