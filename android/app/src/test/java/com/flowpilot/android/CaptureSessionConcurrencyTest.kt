package com.flowpilot.android

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionConcurrencyTest {
    @Test
    fun captureCallsAreSerialized() {
        val mutex = CaptureFrameMutex()
        val executor = Executors.newFixedThreadPool(2)
        val firstEntered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val activeCalls = AtomicInteger()
        val maximumActiveCalls = AtomicInteger()

        try {
            val first = executor.submit {
                mutex.withCapture {
                    val active = activeCalls.incrementAndGet()
                    maximumActiveCalls.updateAndGet { maxOf(it, active) }
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                    activeCalls.decrementAndGet()
                }
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit {
                secondStarted.countDown()
                mutex.withCapture {
                    val active = activeCalls.incrementAndGet()
                    maximumActiveCalls.updateAndGet { maxOf(it, active) }
                    secondEntered.countDown()
                    activeCalls.decrementAndGet()
                }
            }

            assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals(1, maximumActiveCalls.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun closingBorrowedCaptureDoesNotCloseOwnerSession() {
        val borrowed = FakeCloseable()
        val owner = FakeCloseable()

        CaptureSessionLease(borrowed, ownsSession = false).close()
        CaptureSessionLease(owner, ownsSession = true).apply {
            close()
            close()
        }

        assertEquals(0, borrowed.closeCount)
        assertEquals(1, owner.closeCount)
    }

    private class FakeCloseable : Closeable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
