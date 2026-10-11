package com.flowpilot.android

import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class ScriptIntegrityTest {
    @Test
    fun acceptsMatchingSha256CaseInsensitively() {
        val source = "bridge.open_douyin()\nresult = bridge.ocr()"
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        verifyScriptSha256(source, hash.uppercase())
    }

    @Test
    fun rejectsModifiedScriptContent() {
        assertThrows(IOException::class.java) {
            verifyScriptSha256("print('modified')", "0".repeat(64))
        }
    }
}
