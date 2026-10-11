package com.flowpilot.android

import java.util.concurrent.TimeUnit

internal fun awaitDouyinForeground(
    isForeground: () -> Boolean,
    timeoutMillis: Long,
    pollIntervalMillis: Long,
    isCancelled: () -> Boolean = { false },
    wait: (Long) -> Unit = Thread::sleep,
    nowNanos: () -> Long = System::nanoTime,
): Boolean {
    require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
    require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive" }
    val deadline = nowNanos() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (true) {
        if (isCancelled()) return false
        if (isForeground()) return true
        val remainingNanos = deadline - nowNanos()
        if (remainingNanos <= 0) return false
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1)
        try {
            wait(minOf(pollIntervalMillis, remainingMillis))
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        }
    }
}
