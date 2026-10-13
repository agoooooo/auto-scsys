package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAppNavigationTest {
    @Test
    fun signedInNavigationIncludesAccountWithDeviceTasksAndRuns() {
        assertEquals(
            listOf("设备", "任务", "运行", "我的"),
            DeviceAppDestination.entries.map(DeviceAppDestination::label),
        )
    }
}
