package com.flowpilot.android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertNull
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeInstrumentedTest {
    @Test
    fun debugBuildAllowsLanHttpForLocalDevelopment() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allowsCleartext = context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0

        assertTrue(allowsCleartext)
    }

    @Test
    fun launcherReusesTheExistingMainActivityTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityInfo = context.packageManager.getActivityInfo(
            android.content.ComponentName(context, MainActivity::class.java),
            0,
        )

        assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, activityInfo.launchMode)
    }

    @Test
    fun rememberedLoginIsEncryptedStoredAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SessionStore(context)
        val previous = store.readRememberedLogin()
        val expected = RememberedLogin(
            "http://192.168.0.101:8001",
            "test@example.com",
            "test-password",
        )
        try {
            store.saveRememberedLogin(expected)
            assertEquals(expected, store.readRememberedLogin())

            store.clearRememberedLogin()
            assertNull(store.readRememberedLogin())
        } finally {
            if (previous == null) store.clearRememberedLogin()
            else store.saveRememberedLogin(previous)
        }
    }

    @Test
    fun embeddedPythonExecutesOnDevice() {
        ensurePythonStarted()
        val bridge = InstrumentedNativeBridge()
        val result = PythonTaskRuntime.execute(
            """
                bridge.open_douyin()
                ready = bridge.wait_for_douyin(12)
                text = bridge.ocr()
                clicked = bridge.click_text("继续")
                bridge.log("bridge-called")
                stopped = bridge.is_stopped()
                result = text + ":" + str(ready) + ":" + str(clicked) + ":" + str(stopped)
            """.trimIndent(),
            bridge,
        )

        assertEquals("识别结果:True:True:False", result)
        assertTrue(bridge.opened)
        assertEquals(12, bridge.waitTimeoutSeconds)
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

    @Test
    fun bundledChineseRecognizerReadsTextFromLocalImage() {
        val bitmap = Bitmap.createBitmap(900, 220, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawText(
            "FlowPilot 123",
            40f,
            150f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 96f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
        )
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 30, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("OCR result was: ${result.text}", result.text.contains("FlowPilot", ignoreCase = true))
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun ensurePythonStarted() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    }
}

class InstrumentedNativeBridge {
    var opened = false
    var waitTimeoutSeconds = 0
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

    fun waitForDouyin(timeoutSeconds: Int): Boolean {
        waitTimeoutSeconds = timeoutSeconds
        return true
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
