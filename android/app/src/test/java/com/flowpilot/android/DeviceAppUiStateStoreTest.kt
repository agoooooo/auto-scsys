package com.flowpilot.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAppUiStateStoreTest {
    @Test
    fun preservesSupportedNavigationAndScrollState() {
        val state = sanitizeDeviceAppUiState(
            DeviceAppUiState(
                destination = DeviceAppDestination.TASKS.name,
                accountPage = DeviceAppAccountPage.SECURITY.name,
                scrollPosition = 420,
            ),
        )

        assertEquals(DeviceAppDestination.TASKS.name, state.destination)
        assertEquals(DeviceAppAccountPage.SECURITY.name, state.accountPage)
        assertEquals(420, state.scrollPosition)
    }

    @Test
    fun fallsBackForUnknownNavigationAndNegativeScrollPosition() {
        val state = sanitizeDeviceAppUiState(
            DeviceAppUiState(
                destination = "UNKNOWN",
                accountPage = "UNKNOWN",
                scrollPosition = -10,
            ),
        )

        assertEquals(DeviceAppDestination.DEVICE.name, state.destination)
        assertEquals(DeviceAppAccountPage.HOME.name, state.accountPage)
        assertEquals(0, state.scrollPosition)
    }
}
