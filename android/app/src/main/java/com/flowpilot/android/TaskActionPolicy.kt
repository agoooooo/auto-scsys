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

internal fun shouldPollRemoteStop(remoteRequestId: String?, stopRequested: Boolean): Boolean =
    !remoteRequestId.isNullOrBlank() && !stopRequested

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

internal fun defaultStandbyRequestKey(
    userId: String?,
    deviceId: String?,
    loading: Boolean,
    requestedFor: String?,
): String? {
    if (loading || userId.isNullOrBlank() || deviceId.isNullOrBlank()) return null
    val key = "$userId:$deviceId"
    return key.takeIf { it != requestedFor }
}

internal fun shouldRequestBatteryOptimizationExemption(
    exemptionSupported: Boolean,
    alreadyExempt: Boolean,
): Boolean = exemptionSupported && !alreadyExempt

internal fun usesXiaomiAutoStartSettings(manufacturer: String): Boolean =
    manufacturer.contains("xiaomi", ignoreCase = true) ||
        manufacturer.contains("redmi", ignoreCase = true) ||
        manufacturer.contains("poco", ignoreCase = true)

internal fun shouldDirectlyRunRemoteRequest(
    status: String,
    runId: String?,
    accessibilityRequired: Boolean,
    accessibilityEnabled: Boolean,
    deviceRunStatus: String?,
): Boolean = (status == "pending" || (status == "running" && !runId.isNullOrBlank())) &&
    (!accessibilityRequired || accessibilityEnabled) &&
    canStartOcrStandby(deviceRunStatus)

internal fun requiresAccessibility(capabilities: List<String>): Boolean =
    capabilities.any { capability ->
        capability.contains("无障碍", ignoreCase = true) ||
            capability.contains("accessibility", ignoreCase = true) ||
            capability.contains("点击", ignoreCase = true)
    }
