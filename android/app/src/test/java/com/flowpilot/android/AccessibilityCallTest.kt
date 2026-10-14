package com.flowpilot.android

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AccessibilityCallTest {
    @Test
    fun exactAccessibilityTextMatchesArePreferredOverSubstringMatches() {
        assertTrue(accessibilityTextMatches("我", "我", exactOnly = true))
        assertFalse(accessibilityTextMatches("我", "我不信", exactOnly = true))
        assertTrue(accessibilityTextMatches("我", "我不信", exactOnly = false))
    }

    @Test
    fun timedOutQueuedAccessibilityActionCannotRunLater() {
        val actionCalls = AtomicInteger()
        val task = FutureTask(Callable { actionCalls.incrementAndGet() })
        var removedFromQueue = false

        assertThrows(IllegalStateException::class.java) {
            awaitAccessibilityCall(task, 0) { removedFromQueue = true }
        }
        task.run()

        assertTrue(task.isCancelled)
        assertTrue(removedFromQueue)
        assertEquals(0, actionCalls.get())
    }
}
