package com.flowpilot.android

internal fun shouldNotifyPreauthorizedRun(
    status: String,
    runId: String?,
    handled: Boolean,
): Boolean = status == "running" && !runId.isNullOrBlank() && !handled
