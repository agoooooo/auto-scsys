package com.flowpilot.android

internal fun permissionStatusLabel(permission: String, available: Boolean): String =
    if (available) "${permission}已具备" else "缺少${permission}"
