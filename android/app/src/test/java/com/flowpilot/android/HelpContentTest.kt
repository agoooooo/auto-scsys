package com.flowpilot.android

import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {
    @Test
    fun controlledHelpExplainsVersionPermissionsLocalOcrAndCooperativeStop() {
        val sections = controlledAppHelpSections("0.1.0")
        val content = sections.joinToString(" ") { "${it.title} ${it.body}" }

        assertTrue(content.contains("FlowPilot 受控端 · 0.1.0"))
        assertTrue(content.contains("Android 系统中授予"))
        assertTrue(content.contains("设备本地处理"))
        assertTrue(content.contains("bridge.is_stopped()"))
        assertTrue(content.contains("不会强行终止"))
        assertTrue(content.contains("不是安全沙箱"))
    }
}
