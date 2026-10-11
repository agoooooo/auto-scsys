package com.flowpilot.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRefreshPolicyTest {
    @Test
    fun refreshesExpiredTokensAndTokensWithinTheLeadWindow() {
        assertTrue(shouldRefreshAccessToken(900, 1_000, 300))
        assertTrue(shouldRefreshAccessToken(1_300, 1_000, 300))
        assertFalse(shouldRefreshAccessToken(1_301, 1_000, 300))
    }
}
