package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionStatusLabelTest {
    @Test
    fun identifiesAvailableAndMissingPermissions() {
        assertEquals("通知权限已具备", permissionStatusLabel("通知权限", available = true))
        assertEquals("缺少通知权限", permissionStatusLabel("通知权限", available = false))
    }
}
