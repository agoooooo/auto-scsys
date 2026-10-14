package com.flowpilot.android

import com.chaquo.python.Python

internal object PythonTaskRuntime {
    private val bridgeSource = """
        class FlowPilotBridge:
            def open_douyin(self):
                return nativeBridge.openDouyin()
            def wait_for_douyin(self, timeout_seconds=15):
                return nativeBridge.waitForDouyin(timeout_seconds)
            def ocr(self):
                return nativeBridge.ocr()
            def click_text(self, text):
                return nativeBridge.clickText(text)
            def click_text_exact(self, text):
                return nativeBridge.clickTextExact(text)
            def log(self, message):
                return nativeBridge.log(str(message))
            def is_stopped(self):
                return nativeBridge.isStopped()
        bridge = FlowPilotBridge()
    """.trimIndent()

    fun execute(source: String, nativeBridge: Any): String {
        val builtins = Python.getInstance().getBuiltins()
        val globals = builtins.callAttr("dict")
        globals.callAttr("__setitem__", "__name__", "__main__")
        globals.callAttr("__setitem__", "nativeBridge", nativeBridge)
        builtins.callAttr("exec", bridgeSource, globals)
        builtins.callAttr("exec", source, globals)
        return globals.callAttr("get", "result")?.toString()?.takeIf {
            it.isNotBlank() && it != "None"
        }
            ?: "脚本已执行完成"
    }
}
