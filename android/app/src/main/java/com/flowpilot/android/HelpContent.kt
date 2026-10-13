package com.flowpilot.android

internal data class HelpSection(val title: String, val body: String)

internal fun controlledAppHelpSections(versionName: String): List<HelpSection> = listOf(
    HelpSection("应用版本", "FlowPilot 受控端 · $versionName"),
    HelpSection(
        "设备绑定与待命",
        "登录普通用户账号并绑定此手机。要接收远程任务，请开启远程运行待命并允许 Android 显示持续通知。",
    ),
    HelpSection(
        "脚本接收与运行",
        "先检查发布来源、版本说明和能力列表，再确认接收脚本。屏幕捕获和无障碍权限只能由你在 Android 系统中授予。",
    ),
    HelpSection(
        "停止任务",
        "停止请求会在受控端轮询后传入脚本，并在下一个 bridge.is_stopped() 检查点生效；不会强行终止阻塞中的代码。",
    ),
    HelpSection(
        "隐私与运行边界",
        "OCR 结果在设备本地处理。Python 脚本运行在 App 进程内，不是安全沙箱；只接收和运行你信任的脚本。",
    ),
)
