package com.flowpilot.android

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal fun <T> awaitAccessibilityCall(
    task: FutureTask<T>,
    timeoutSeconds: Long,
    onTimeout: () -> Unit,
): T {
    try {
        return task.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (exception: TimeoutException) {
        task.cancel(false)
        onTimeout()
        throw IllegalStateException("操作抖音界面超时，请重试", exception)
    }
}
