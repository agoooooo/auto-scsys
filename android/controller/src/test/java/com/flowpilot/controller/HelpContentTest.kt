package com.flowpilot.controller

import org.junit.Assert.assertTrue
import org.junit.Test

class HelpContentTest {
    @Test
    fun controllerHelpExplainsVersionResponsibilitiesAndStopLimitations() {
        val sections = controllerHelpSections("0.1.0")
        val content = sections.joinToString(" ") { "${it.title} ${it.body}" }

        assertTrue(content.contains("FlowPilot 控制端 · 0.1.0"))
        assertTrue(content.contains("在线受控设备"))
        assertTrue(content.contains("不包含脚本运行时"))
        assertTrue(content.contains("bridge.is_stopped()"))
        assertTrue(content.contains("不能强行终止"))
        assertTrue(content.contains("HTTPS"))
    }
}
