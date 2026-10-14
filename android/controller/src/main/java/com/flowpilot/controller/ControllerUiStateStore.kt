package com.flowpilot.controller

import android.content.Context

internal data class ControllerUiState(
    val destination: String = "DEVICES",
    val accountPage: String = "HOME",
    val offlineDevicesExpanded: Boolean = false,
    val selectedDeviceId: String? = null,
    val selectedDeviceIds: Set<String> = emptySet(),
    val selectedTaskIds: Set<String> = emptySet(),
)

internal fun sanitizeControllerUiState(state: ControllerUiState): ControllerUiState =
    state.copy(
        destination = ControllerDestination.entries.firstOrNull { it.name == state.destination }
            ?.name ?: ControllerDestination.DEVICES.name,
        accountPage = ControllerAccountPage.entries.firstOrNull { it.name == state.accountPage }
            ?.name ?: ControllerAccountPage.HOME.name,
    )

internal class ControllerUiStateStore(context: Context) {
    private val preferences = context.getSharedPreferences("flowpilot_controller_ui", Context.MODE_PRIVATE)

    fun read(): ControllerUiState = sanitizeControllerUiState(ControllerUiState(
        destination = preferences.getString(KEY_DESTINATION, null) ?: "DEVICES",
        accountPage = preferences.getString(KEY_ACCOUNT_PAGE, null) ?: "HOME",
        offlineDevicesExpanded = preferences.getBoolean(KEY_OFFLINE_EXPANDED, false),
        selectedDeviceId = preferences.getString(KEY_SELECTED_DEVICE, null),
        selectedDeviceIds = preferences.getStringSet(KEY_SELECTED_DEVICES, emptySet()).orEmpty().toSet(),
        selectedTaskIds = preferences.getStringSet(KEY_SELECTED_TASKS, emptySet()).orEmpty().toSet(),
    ))

    fun save(state: ControllerUiState) {
        preferences.edit()
            .putString(KEY_DESTINATION, state.destination)
            .putString(KEY_ACCOUNT_PAGE, state.accountPage)
            .putBoolean(KEY_OFFLINE_EXPANDED, state.offlineDevicesExpanded)
            .putString(KEY_SELECTED_DEVICE, state.selectedDeviceId)
            .putStringSet(KEY_SELECTED_DEVICES, state.selectedDeviceIds)
            .putStringSet(KEY_SELECTED_TASKS, state.selectedTaskIds)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_DESTINATION = "destination"
        const val KEY_ACCOUNT_PAGE = "account_page"
        const val KEY_OFFLINE_EXPANDED = "offline_devices_expanded"
        const val KEY_SELECTED_DEVICE = "selected_device"
        const val KEY_SELECTED_DEVICES = "selected_devices"
        const val KEY_SELECTED_TASKS = "selected_tasks"
    }
}
