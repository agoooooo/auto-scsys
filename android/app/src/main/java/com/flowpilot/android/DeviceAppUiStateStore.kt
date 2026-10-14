package com.flowpilot.android

import android.content.Context

internal data class DeviceAppUiState(
    val destination: String = "DEVICE",
    val accountPage: String = "HOME",
    val scrollPosition: Int = 0,
)

internal fun sanitizeDeviceAppUiState(state: DeviceAppUiState): DeviceAppUiState =
    state.copy(
        destination = DeviceAppDestination.entries.firstOrNull { it.name == state.destination }
            ?.name ?: DeviceAppDestination.DEVICE.name,
        accountPage = DeviceAppAccountPage.entries.firstOrNull { it.name == state.accountPage }
            ?.name ?: DeviceAppAccountPage.HOME.name,
        scrollPosition = state.scrollPosition.coerceAtLeast(0),
    )

internal class DeviceAppUiStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("flowpilot_device_ui", Context.MODE_PRIVATE)

    fun read(userId: String?, destination: String, accountPage: String): DeviceAppUiState {
        if (userId.isNullOrBlank()) return DeviceAppUiState()
        val safeDestination = DeviceAppDestination.entries.firstOrNull { it.name == destination }
            ?.name ?: DeviceAppDestination.DEVICE.name
        val safeAccountPage = DeviceAppAccountPage.entries.firstOrNull { it.name == accountPage }
            ?.name ?: DeviceAppAccountPage.HOME.name
        val key = pageKey(userId, safeDestination, safeAccountPage)
        return sanitizeDeviceAppUiState(
            DeviceAppUiState(
                destination = preferences.getString(destinationKey(userId), null)
                    ?: DeviceAppDestination.DEVICE.name,
                accountPage = preferences.getString(accountPageKey(userId), null)
                    ?: DeviceAppAccountPage.HOME.name,
                scrollPosition = preferences.getInt(key, 0),
            ),
        )
    }

    fun saveNavigation(userId: String, destination: String, accountPage: String) {
        val safe = sanitizeDeviceAppUiState(
            DeviceAppUiState(destination = destination, accountPage = accountPage),
        )
        preferences.edit()
            .putString(destinationKey(userId), safe.destination)
            .putString(accountPageKey(userId), safe.accountPage)
            .apply()
    }

    fun saveScrollPosition(userId: String, destination: String, accountPage: String, position: Int) {
        val safe = sanitizeDeviceAppUiState(
            DeviceAppUiState(destination, accountPage, position),
        )
        preferences.edit()
            .putInt(pageKey(userId, safe.destination, safe.accountPage), safe.scrollPosition)
            .apply()
    }

    fun clear(userId: String?) {
        if (userId.isNullOrBlank()) return
        val prefix = "$userId:"
        preferences.edit().apply {
            remove(destinationKey(userId))
            remove(accountPageKey(userId))
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
        }.apply()
    }

    private fun destinationKey(userId: String) = "$userId:destination"
    private fun accountPageKey(userId: String) = "$userId:account_page"
    private fun pageKey(userId: String, destination: String, accountPage: String) =
        "$userId:$destination:$accountPage:scroll"
}
