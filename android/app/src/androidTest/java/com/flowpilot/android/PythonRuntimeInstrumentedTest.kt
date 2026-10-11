package com.flowpilot.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PythonRuntimeInstrumentedTest {
    @Test
    fun embeddedPythonExecutesOnDevice() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))

        val builtins = Python.getInstance().getBuiltins()
        val globals = builtins.callAttr("dict")
        builtins.callAttr("exec", "result = sum([1, 2, 3])", globals)

        assertEquals(6, builtins.callAttr("eval", "result", globals).toInt())
    }
}
