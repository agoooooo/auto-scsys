package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiBaseUrlTest {
    @Test
    fun defaultsToEmulatorHostOnlyForEmulators() {
        assertEquals("http://10.0.2.2:8001", defaultApiBaseUrl(isEmulator = true))
        assertEquals("http://192.168.0.105:8001", defaultApiBaseUrl(isEmulator = false))
    }

    @Test
    fun normalizesLanHttpAddressAndTrailingSlash() {
        assertEquals(
            "http://192.168.0.105:8001",
            normalizeApiBaseUrl(" http://192.168.0.105:8001/ ", allowCleartext = true),
        )
    }

    @Test
    fun acceptsHttpsAndNormalizesSchemeCase() {
        assertEquals("https://api.example.com", normalizeApiBaseUrl("HTTPS://API.EXAMPLE.COM"))
    }

    @Test
    fun releaseConfigurationRejectsHttpBeforeApiRequests() {
        assertEquals(
            "https://api.example.com",
            normalizeApiBaseUrl("https://api.example.com", allowCleartext = false),
        )
        assertThrows(IllegalArgumentException::class.java) {
            normalizeApiBaseUrl("http://192.168.0.105:8001", allowCleartext = false)
        }
    }

    @Test
    fun debugConfigurationAllowsHttpForTrustedLanDevelopment() {
        assertEquals(
            "http://192.168.0.105:8001",
            normalizeApiBaseUrl("http://192.168.0.105:8001", allowCleartext = true),
        )
    }

    @Test
    fun defaultCleartextPolicyFollowsBuildVariant() {
        val acceptsHttp = runCatching {
            normalizeApiBaseUrl("http://192.168.0.105:8001")
        }.isSuccess

        assertEquals(BuildConfig.DEBUG, acceptsHttp)
    }

    @Test
    fun rejectsNonHttpAndNonRootAddresses() {
        listOf(
            "ftp://api.example.com",
            "http://api.example.com/v1",
            "http://user:pass@api.example.com",
            "http://api.example.com?token=x",
            "http://api.example.com:0",
        ).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { normalizeApiBaseUrl(value) }
        }
    }
}
