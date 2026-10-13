package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAppNavigationTest {
    @Test
    fun signedInNavigationSeparatesDeviceTasksAndRuns() {
        assertEquals(
            listOf("设备", "任务", "运行"),
            DeviceAppDestination.entries.map(DeviceAppDestination::label),
        )
    }
}
