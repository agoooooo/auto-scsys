package com.flowpilot.android

internal fun runStatusLabel(status: String): String = when (status) {
    "awaiting_permission" -> "等待系统授权"
    "pending" -> "待确认"
    "running" -> "执行中"
    "success" -> "已完成"
    "stopped" -> "已停止"
    "failure" -> "失败"
    else -> "未知状态"
}

internal fun runVersionLabel(version: String?): String? =
    version?.takeIf(String::isNotBlank)?.let { "版本：$it" }

internal fun remoteRunStatusLabel(status: String): String = when (status) {
    "pending" -> "待确认"
    "running" -> "已授权"
    else -> runStatusLabel(status)
}
