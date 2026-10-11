package com.flowpilot.android

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val scope = ScriptCacheScope("http://api.example", "user-1", "device-1")
    private val task = AvailableTask(
        id = "task-1",
        name = "OCR",
        description = "Recognize order status",
        version = "v1",
        capabilities = listOf("OCR"),
        source = "publisher",
    )

    @Test
    fun storesOnlyVerifiedScriptsAndReturnsCachedTaskMetadata() {
        val cache = ScriptCache(temporaryFolder.newFolder("scripts"))
        val script = script("print('v1')")

        cache.save(scope, task, script)

        assertEquals(script.copy(isCached = true), cache.load(scope, task))
        assertEquals(task, cache.latest(scope, task)?.task)
        assertNull(cache.load(scope.copy(userId = "other-user"), task))
    }

    @Test
    fun leavesLastGoodVersionAvailableWhenNewVersionCannotBeVerified() {
        val cache = ScriptCache(temporaryFolder.newFolder("scripts"))
        val availableV1 = task.copy(description = "v1-only permission details")
        cache.save(scope, availableV1, script("print('v1')"))

        assertThrows(IOException::class.java) {
            cache.save(scope, task.copy(version = "v2"), script("print('modified')", "0".repeat(64)))
        }

        assertEquals("v1", cache.latest(scope, task.copy(version = "v2"))?.task?.version)
        assertEquals(
            "v1-only permission details",
            cache.latest(scope, task.copy(version = "v2"))?.task?.description,
        )
        assertEquals("print('v1')", cache.latest(scope, task.copy(version = "v2"))?.script?.source)
    }

    @Test
    fun keepsOnlyTheTwoMostRecentVersionsPerTask() {
        val cache = ScriptCache(temporaryFolder.newFolder("scripts"))
        cache.save(scope, task, script("print('v1')"))
        Thread.sleep(10)
        cache.save(scope, task.copy(version = "v2"), script("print('v2')"))
        Thread.sleep(10)
        cache.save(scope, task.copy(version = "v3"), script("print('v3')"))

        assertNull(cache.load(scope, task))
        assertNotNull(cache.load(scope, task.copy(version = "v2")))
        assertEquals("v3", cache.latest(scope, task)?.task?.version)
    }

    @Test
    fun rejectsCorruptedCachedContent() {
        val directory = temporaryFolder.newFolder("scripts")
        val cache = ScriptCache(directory)
        cache.save(scope, task, script("print('v1')"))
        val artifact = directory.listFiles()!!.single()
        artifact.writeBytes(byteArrayOf(0, 0, 0, 2))

        assertThrows(IOException::class.java) { cache.load(scope, task) }
        assertThrows(IOException::class.java) { cache.latest(scope, task) }
    }

    private fun script(source: String, expectedHash: String = sha256(source)) =
        DownloadedScript("task.py", source, expectedHash)

    private fun sha256(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
