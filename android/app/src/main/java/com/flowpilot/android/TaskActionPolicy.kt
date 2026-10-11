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
