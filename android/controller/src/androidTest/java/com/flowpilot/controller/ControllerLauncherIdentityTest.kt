package com.flowpilot.controller

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ControllerLauncherIdentityTest {
    @Test
    fun launcherIdentifiesTheControllerApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationInfo

        assertEquals("FlowPilot 控制端", application.loadLabel(context.packageManager).toString())
        assertNotEquals(0, application.icon)
        assertNotNull(context.packageManager.getApplicationIcon(application))
    }
}
