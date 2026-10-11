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

internal fun remoteRunCanBeApproved(
    accessibilityEnabled: Boolean,
    runStatus: String?,
): Boolean = accessibilityEnabled && runStatus !in setOf("running", "awaiting_permission")

internal fun requiresScreenCapture(capabilities: List<String>): Boolean =
    capabilities.any { capability ->
        capability.contains("屏幕", ignoreCase = true) ||
            capability.contains("screen", ignoreCase = true) ||
            capability.contains("OCR", ignoreCase = true) ||
            capability.contains("文字识别", ignoreCase = true) ||
            capability.contains("界面识别", ignoreCase = true) ||
            capability.contains("读屏", ignoreCase = true)
    }

internal fun canAutoRunRemote(capabilities: List<String>): Boolean =
    !requiresScreenCapture(capabilities)

internal fun requiresAccessibility(capabilities: List<String>): Boolean =
    capabilities.any { capability ->
        capability.contains("无障碍", ignoreCase = true) ||
            capability.contains("accessibility", ignoreCase = true) ||
            capability.contains("点击", ignoreCase = true)
    }
