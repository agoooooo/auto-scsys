package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleClickGateTest {
    @Test
    fun allowsOneClickAndRejectsSubsequentAttempts() {
        val gate = SingleClickGate()
        var actionCount = 0

        assertTrue(gate.click {
            actionCount++
            true
        })
        assertThrows(IllegalStateException::class.java) {
            gate.click {
                actionCount++
                true
            }
        }
        assertEquals(1, actionCount)
    }

    @Test
    fun failedClickCannotBeRetried() {
        val gate = SingleClickGate()

        assertFalse(gate.click { false })
        assertThrows(IllegalStateException::class.java) {
            gate.click { true }
        }
    }

    @Test
    fun thrownClickCannotBeRetried() {
        val gate = SingleClickGate()

        assertThrows(IllegalStateException::class.java) {
            gate.click { throw IllegalStateException("click failed") }
        }
        assertThrows(IllegalStateException::class.java) {
            gate.click { true }
        }
    }
}
