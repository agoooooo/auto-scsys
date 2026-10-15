package com.flowpilot.controller

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
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

    @Test
    fun appUpdateValidatesTheInstalledApkAndSharesStagedUpdatesWithTheInstaller() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        validateControllerAppUpdateApk(
            File(context.applicationInfo.sourceDir),
            BuildConfig.APPLICATION_ID,
            BuildConfig.VERSION_CODE.toLong(),
            BuildConfig.VERSION_NAME,
            context.packageManager,
        )

        val updateDirectory = File(context.cacheDir, "updates").apply { mkdirs() }
        val stagedApk = File.createTempFile("update-", ".apk", updateDirectory)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                stagedApk,
            )
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
        } finally {
            stagedApk.delete()
        }
    }
}
