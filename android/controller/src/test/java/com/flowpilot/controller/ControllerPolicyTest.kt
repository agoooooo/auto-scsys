package com.flowpilot.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color

class ControllerPolicyTest {
    @Test
    fun dispatchRequiresOnlineDevicePublishedTaskAndVersion() {
        assertTrue(canDispatchControllerTask("online", "published", "v1.0"))
        assertFalse(canDispatchControllerTask("offline", "published", "v1.0"))
        assertFalse(canDispatchControllerTask("online", "draft", "v1.0"))
        assertFalse(canDispatchControllerTask("online", "published", ""))
    }

    @Test
    fun onlyPendingAndRunningRequestsAreActive() {
        assertTrue(isControllerRunActive("queued"))
        assertTrue(isControllerRunActive("pending"))
        assertTrue(isControllerRunActive("running"))
        assertFalse(isControllerRunActive("success"))
        assertFalse(isControllerRunActive("expired"))
    }

    @Test
    fun knownRunStatesHaveLocalizedLabels() {
        assertEquals("等待受控端接收", controllerRunStatusLabel("pending"))
        assertEquals("运行失败", controllerRunStatusLabel("failure"))
        assertEquals("状态：custom", controllerRunStatusLabel("custom"))
    }

    @Test
    fun apiUrlMustBeAServiceRootAndReleaseRequiresHttps() {
        assertEquals("http://localhost:8001", normalizeControllerApiUrl("http://LOCALHOST:8001/"))
        assertEquals("https://example.com", normalizeControllerApiUrl("https://example.com", allowCleartext = false))
        assertFalse(runCatching {
            normalizeControllerApiUrl("http://localhost:8001", allowCleartext = false)
        }.isSuccess)
        assertFalse(runCatching { normalizeControllerApiUrl("https://example.com/api") }.isSuccess)
    }

    @Test
    fun deviceStatusLabelsDistinguishDisabledFromOffline() {
        assertEquals("在线", controllerDeviceStatusLabel("online"))
        assertEquals("离线", controllerDeviceStatusLabel("offline"))
        assertEquals("已停用", controllerDeviceStatusLabel("disabled"))
    }

    @Test
    fun offlineDeviceGroupStartsCollapsedAndOnlyOfflineDevicesCanBeUnbound() {
        assertFalse(isControllerDeviceGroupExpandedByDefault("offline"))
        assertTrue(isControllerDeviceGroupExpandedByDefault("online"))
        assertTrue(canUnbindControllerDevice("offline"))
        assertFalse(canUnbindControllerDevice("online"))
        assertFalse(canUnbindControllerDevice("disabled"))
    }

    @Test
    fun onlineDevicesAppearFirstAndDispatchTargetsCoverSelectedDeviceTaskPairs() {
        val devices = listOf(
            ControllerDevice("offline", "旧设备", "model", "offline"),
            ControllerDevice("online-b", "Beta", "model", "online"),
            ControllerDevice("online-a", "Alpha", "model", "online"),
        )
        val tasks = listOf(
            ControllerTask("published", "Published", "", "published", "v1.0"),
            ControllerTask("published-2", "Published 2", "", "published", "v2.0"),
            ControllerTask("draft", "Draft", "", "draft", "v1.0"),
            ControllerTask("no-version", "No version", "", "published", ""),
        )

        assertEquals(
            listOf("Alpha", "Beta", "旧设备"),
            orderControllerDevices(devices).map(ControllerDevice::name),
        )
        assertEquals(
            listOf(
                "online-a:published",
                "online-a:published-2",
                "online-b:published",
                "online-b:published-2",
            ),
            buildControllerDispatchTargets(
                devices,
                tasks,
                selectedDeviceIds = setOf("online-a", "online-b", "offline"),
                selectedTaskIds = setOf("published", "published-2", "draft", "no-version"),
            ).map { "${it.device.id}:${it.task.id}" },
        )
    }

    @Test
    fun deviceGroupsAreOrderedByAvailabilityAndOmitEmptyGroups() {
        val devices = listOf(
            ControllerDevice("other", "Other", "model", "maintenance"),
            ControllerDevice("disabled", "Disabled", "model", "disabled"),
            ControllerDevice("offline", "Offline", "model", "offline"),
            ControllerDevice("online", "Online", "model", "online"),
        )

        assertEquals(
            listOf("在线设备", "离线设备", "其他状态"),
            groupControllerDevices(devices).map(ControllerDeviceGroup::label),
        )
        assertEquals(
            listOf(1, 1, 1),
            groupControllerDevices(devices).map { it.devices.size },
        )
        assertEquals(
            listOf("other", "offline", "online"),
            visibleControllerDevices(devices).map(ControllerDevice::id),
        )
        assertFalse(
            groupControllerDevices(devices)
                .flatMap(ControllerDeviceGroup::devices)
                .any { it.status == "disabled" },
        )
        assertEquals(
            listOf("在线设备"),
            groupControllerDevices(listOf(devices.last())).map(ControllerDeviceGroup::label),
        )
    }

    @Test
    fun signedInNavigationHasDispatchRunsAndAccountDestinations() {
        assertEquals(
            listOf("设备", "任务", "运行", "我的"),
            ControllerDestination.entries.map(ControllerDestination::label),
        )
        assertEquals(
            listOf("我的", "个人资料", "账号与安全"),
            ControllerAccountPage.entries.map(ControllerAccountPage::title),
        )
    }

    @Test
    fun controllerThemeUsesGreenAccentAndLightSurfaces() {
        assertEquals(Color(0xFF07C160), ControllerColorScheme.primary)
        assertEquals(Color(0xFFF5F7F6), ControllerColorScheme.background)
        assertEquals(Color.White, ControllerColorScheme.surface)
    }
}
