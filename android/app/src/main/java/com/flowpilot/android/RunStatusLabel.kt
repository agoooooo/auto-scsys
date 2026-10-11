package com.flowpilot.android

internal fun runStatusLabel(status: String): String = when (status) {
    "awaiting_permission" -> "等待系统授权"
    "running" -> "执行中"
    "success" -> "已完成"
    "stopped" -> "已停止"
    "failure" -> "失败"
    else -> "未知状态"
}
