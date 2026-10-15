package com.flowpilot.controller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.ui.graphics.Color
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ControllerPolicyTest {
    @Test
    fun dispatchRequiresOnlineDevicePublishedTaskAndVersion() {
        assertTrue(canDispatchControllerTask("online", "published", "v1.0"))
        assertFalse(canDispatchControllerTask("offline", "published", "v1.0"))
        assertFalse(canDispatchControllerTask("online", "draft", "v1.0"))
        assertFalse(canDispatchControllerTask("online", "published", ""))
    }

    @Test
    fun remoteAssistCardActionIsAvailableOnlyForOnlineDevices() {
        assertTrue(canStartControllerRemoteAssist("online"))
        assertFalse(canStartControllerRemoteAssist("offline"))
        assertFalse(canStartControllerRemoteAssist("disabled"))
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
    fun expiredSessionDuringTokenRefreshNotifiesTheActivityToReturnToLogin() {
        val callbackCount = AtomicInteger()
        ServerSocket(0).use { server ->
            val responder = thread {
                repeat(2) {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val body = """{"detail":"session expired"}""".toByteArray()
                        socket.getOutputStream().write(
                            ("HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body,
                        )
                    }
                }
            }
            val result = runCatching {
                ControllerApi(
                    "http://127.0.0.1:${server.localPort}",
                    initialAccessToken = "expired-token",
                    onSessionExpired = { callbackCount.incrementAndGet() },
                ).profile("expired-token")
            }
            responder.join(5_000)

            assertTrue(result.exceptionOrNull() is ControllerApiException)
            assertEquals(1, callbackCount.get())
            assertFalse(responder.isAlive)
        }
    }

    @Test
    fun endingRemoteAssistUsesAuthenticatedEndEndpoint() {
        val requestLine = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        ServerSocket(0).use { server ->
            val responder = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    requestLine.set(reader.readLine())
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Authorization:", ignoreCase = true)) {
                            authorization.set(header.substringAfter(':').trim())
                        }
                    }
                    val body = """{"id":"session-123","status":"ended"}""".toByteArray()
                    socket.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray() + body,
                    )
                }
            }

            ControllerApi(
                "http://127.0.0.1:${server.localPort}",
                initialAccessToken = "controller-token",
            ).endRemoteAssistSession("controller-token", "session-123")
            responder.join(5_000)

            assertEquals("POST /remote-sessions/session-123/end HTTP/1.1", requestLine.get())
            assertEquals("Bearer controller-token", authorization.get())
            assertFalse(responder.isAlive)
        }
    }

    @Test
    fun deviceStatusLabelsDistinguishDisabledFromOffline() {
        assertEquals("在线", controllerDeviceStatusLabel("online"))
        assertEquals("离线", controllerDeviceStatusLabel("offline"))
        assertEquals("已停用", controllerDeviceStatusLabel("disabled"))
    }

    @Test
    fun deviceModelIsHiddenWhenItRepeatsTheDeviceName() {
        assertFalse(shouldShowControllerDeviceModel("REDMI 25102RKBEC", "redmi 25102rkbec"))
        assertFalse(shouldShowControllerDeviceModel("Phone", ""))
        assertTrue(shouldShowControllerDeviceModel("Phone", "Model X"))
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
    fun restoredUiStateKeepsValidSelectionsAndFallsBackFromUnknownRoutes() {
        val saved = ControllerUiState(
            destination = "TASKS",
            accountPage = "SECURITY",
            offlineDevicesExpanded = true,
            selectedDeviceId = "device-1",
            selectedDeviceIds = setOf("device-1"),
            selectedTaskIds = setOf("task-1"),
        )
        assertEquals(saved, sanitizeControllerUiState(saved))

        val unknown = sanitizeControllerUiState(saved.copy(destination = "UNKNOWN", accountPage = "UNKNOWN"))
        assertEquals("DEVICES", unknown.destination)
        assertEquals("HOME", unknown.accountPage)
        assertEquals(saved.selectedDeviceIds, unknown.selectedDeviceIds)
        assertEquals(saved.selectedTaskIds, unknown.selectedTaskIds)
    }

    @Test
    fun scrollPositionStorageIsScopedToTheControllerPage() {
        assertEquals(
            "scroll:TASKS:HOME",
            controllerScrollPositionKey("TASKS", "HOME"),
        )
        assertEquals(
            "scroll:DEVICES:HOME",
            controllerScrollPositionKey("UNKNOWN", "UNKNOWN"),
        )
    }

    @Test
    fun controllerThemeUsesGreenAccentAndLightSurfaces() {
        assertEquals(Color(0xFF07C160), ControllerColorScheme.primary)
        assertEquals(Color(0xFFF5F7F6), ControllerColorScheme.background)
        assertEquals(Color.White, ControllerColorScheme.surface)
    }
}
