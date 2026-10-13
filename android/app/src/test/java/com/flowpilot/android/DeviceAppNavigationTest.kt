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
        assertEquals(
            listOf("我的", "个人资料", "账号与安全"),
            DeviceAppAccountPage.entries.map(DeviceAppAccountPage::title),
        )
    }
}
