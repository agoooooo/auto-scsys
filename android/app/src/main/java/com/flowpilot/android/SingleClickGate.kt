package com.flowpilot.android

import java.util.concurrent.atomic.AtomicBoolean

internal class SingleClickGate {
    private val used = AtomicBoolean(false)

    fun click(action: () -> Boolean): Boolean {
        check(used.compareAndSet(false, true)) { "每次运行只允许点击一次" }
        return action()
    }
}
