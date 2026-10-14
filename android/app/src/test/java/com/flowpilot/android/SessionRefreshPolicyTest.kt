package com.flowpilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SessionRefreshPolicyTest {
    @Test
    fun refreshesExpiredTokensAndTokensWithinTheLeadWindow() {
        assertTrue(shouldRefreshAccessToken(900, 1_000, 300))
        assertTrue(shouldRefreshAccessToken(1_300, 1_000, 300))
        assertFalse(shouldRefreshAccessToken(1_301, 1_000, 300))
    }

    @Test
    fun unauthorizedApiResponseNotifiesSessionExpiry() {
        val callbackCount = AtomicInteger()
        ServerSocket(0).use { server ->
            val responder = thread {
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
            val result = runCatching {
                ApiClient("http://127.0.0.1:${server.localPort}") {
                    callbackCount.incrementAndGet()
                }.getCurrentUser("expired-token")
            }
            responder.join(5_000)

            assertTrue(result.exceptionOrNull() is ApiException)
            assertEquals(1, callbackCount.get())
            assertFalse(responder.isAlive)
        }
    }

    @Test
    fun successfulDeviceHeartbeatMarksOnlyThatBoundDeviceOnline() {
        val offlineDevice = BoundDevice("device-1", "Phone", "Model", "offline")
        assertEquals("online", markDeviceOnlineAfterHeartbeat(offlineDevice, "device-1")?.status)
        assertEquals(offlineDevice, markDeviceOnlineAfterHeartbeat(offlineDevice, "other-device"))
    }
}
