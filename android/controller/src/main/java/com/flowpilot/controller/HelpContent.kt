package com.flowpilot.controller

internal data class ControllerHelpSection(val title: String, val body: String)

internal fun controllerHelpSections(versionName: String): List<ControllerHelpSection> = listOf(
    ControllerHelpSection("应用版本", "FlowPilot 控制端 · $versionName"),
    ControllerHelpSection(
        "开始使用",
        "登录普通用户账号，选择在线受控设备和已发布任务，再下发运行请求。设备需先接收并确认对应脚本版本。",
    ),
    ControllerHelpSection(
        "跟踪运行",
        "控制端会显示受控端报告的请求状态、运行状态和安全结果摘要；重新打开页面后会读取活动请求并继续跟踪。",
    ),
    ControllerHelpSection(
        "停止任务",
        "停止请求由受控端轮询，并在脚本下一个 bridge.is_stopped() 检查点响应；控制端不能强行终止设备进程。",
    ),
    ControllerHelpSection(
        "职责与连接",
        "控制端只负责派发和跟踪，不包含脚本运行时。Debug HTTP 仅用于可信局域网开发；对外服务需使用 HTTPS。",
    ),
)
