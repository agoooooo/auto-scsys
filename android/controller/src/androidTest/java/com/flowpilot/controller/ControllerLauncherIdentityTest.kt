package com.flowpilot.controller

import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun rememberedCredentialsAreEncryptedAndSurviveSessionLogoutUntilDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ControllerSessionStore(context)
        store.clear()
        store.clearRememberedLogin()
        store.setRememberPasswordEnabled(true)

        val password = "controller-test-password"
        store.saveRememberedLogin("https://api.example.test", "user@example.test", password)
        assertEquals(
            ControllerRememberedLogin("https://api.example.test", "user@example.test", password),
            store.readRememberedLogin(),
        )
        val encryptedPreferences = File(context.dataDir, "shared_prefs/flowpilot_controller_secure.xml")
        var persistedPreferences = ""
        val persistDeadline = System.currentTimeMillis() + 1_000
        while (persistedPreferences.isEmpty() && System.currentTimeMillis() < persistDeadline) {
            persistedPreferences = encryptedPreferences.takeIf(File::exists)?.readText().orEmpty()
            if (persistedPreferences.isEmpty()) Thread.sleep(20)
        }
        assertFalse(persistedPreferences.contains(password))

        store.save(ControllerSession("https://api.example.test", "session-token", "user@example.test", "User"))
        store.clear()
        assertNull(store.read())
        assertEquals(password, store.readRememberedLogin()?.password)

        store.setRememberPasswordEnabled(false)
        assertNull(store.readRememberedLogin())
    }
}
