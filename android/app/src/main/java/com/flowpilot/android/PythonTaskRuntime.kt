package com.flowpilot.android

import com.chaquo.python.Python

internal object PythonTaskRuntime {
    private val bridgeSource = """
        class FlowPilotBridge:
            def open_douyin(self):
                return nativeBridge.openDouyin()
            def ocr(self):
                return nativeBridge.ocr()
            def click_text(self, text):
                return nativeBridge.clickText(text)
            def log(self, message):
                return nativeBridge.log(str(message))
            def is_stopped(self):
                return nativeBridge.isStopped()
        bridge = FlowPilotBridge()
    """.trimIndent()

    fun execute(source: String, nativeBridge: Any): String {
        val builtins = Python.getInstance().getBuiltins()
        val globals = builtins.callAttr("dict")
        globals.callAttr("__setitem__", "nativeBridge", nativeBridge)
        builtins.callAttr("exec", bridgeSource, globals)
        builtins.callAttr("exec", source, globals)
        return globals.callAttr("__getitem__", "result")?.toString()?.takeIf(String::isNotBlank)
            ?: "脚本已执行完成"
    }
}
