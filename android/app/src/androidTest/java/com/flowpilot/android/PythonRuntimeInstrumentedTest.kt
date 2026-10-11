package com.flowpilot.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeInstrumentedTest {
    @Test
    fun embeddedPythonExecutesOnDevice() {
        ensurePythonStarted()
        val bridge = InstrumentedNativeBridge()
        val result = PythonTaskRuntime.execute(
            """
                bridge.open_douyin()
                text = bridge.ocr()
                clicked = bridge.click_text("继续")
                bridge.log("bridge-called")
                stopped = bridge.is_stopped()
                result = text + ":" + str(clicked) + ":" + str(stopped)
            """.trimIndent(),
            bridge,
        )

        assertEquals("识别结果:True:False", result)
        assertTrue(bridge.opened)
        assertEquals(1, bridge.ocrCalls)
        assertEquals("继续", bridge.clickedText)
        assertEquals("bridge-called", bridge.loggedMessage)
        assertFalse(bridge.stopped)
    }

    @Test
    fun scriptWithoutResultUsesCompletionSummary() {
        ensurePythonStarted()

        val result = PythonTaskRuntime.execute("pass", InstrumentedNativeBridge())

        assertEquals("脚本已执行完成", result)
    }

    private fun ensurePythonStarted() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    }
}

class InstrumentedNativeBridge {
    var opened = false
    var ocrCalls = 0
    var clickedText: String? = null
    var loggedMessage: String? = null
    var stopped = false

    fun openDouyin(): Boolean {
        opened = true
        return true
    }

    fun ocr(): String {
        ocrCalls++
        return "识别结果"
    }

    fun clickText(text: String): Boolean {
        clickedText = text
        return true
    }

    fun log(message: String) {
        loggedMessage = message
    }

    fun isStopped(): Boolean = stopped
}
