package com.flowpilot.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun signedInNavigationHasDispatchRunsAndAccountDestinations() {
        assertEquals(
            listOf("派发", "运行", "账号"),
            ControllerDestination.entries.map(ControllerDestination::label),
        )
    }
}
