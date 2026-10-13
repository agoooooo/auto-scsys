package com.flowpilot.android

internal object RunResultSummary {
    fun forServer(status: String, localSummary: String): String = when (status) {
        "success" -> "任务完成"
        "stopped" -> "任务已停止"
        else -> when {
            "未检测到已安装的抖音或抖音极速版" in localSummary ||
                "无法启动已安装的抖音应用" in localSummary ->
                "设备未安装抖音或抖音极速版，或无法启动目标应用"
            "屏幕捕获授权" in localSummary ->
                "缺少有效的屏幕捕获授权，请在设备上重新授权"
            "FlowPilot 自动化操作服务" in localSummary ||
                "FlowPilot 无障碍服务" in localSummary ->
                "FlowPilot 无障碍服务未启用，请在设备设置中开启"
            else -> "任务执行失败，请查看设备端运行记录"
        }
    }
}
