package com.flowpilot.controller

import androidx.compose.ui.graphics.Color
import java.time.DateTimeException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val MAX_VISIBLE_CONTROLLER_RUN_HISTORY = 10

internal fun visibleControllerRunHistory(
    records: List<ControllerRunHistory>,
): List<ControllerRunHistory> = records.take(MAX_VISIBLE_CONTROLLER_RUN_HISTORY)

internal fun controllerRunHistoryStatusColor(status: String): Color = when (status) {
    "failure" -> ControllerColorScheme.error
    "stopped", "expired", "rejected" -> ControllerColorScheme.onSurfaceVariant
    else -> ControllerColorScheme.primary
}

internal fun formatControllerRunTime(
    timestamp: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (timestamp.isBlank()) return "时间未知"
    return try {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(zoneId)
            .format(OffsetDateTime.parse(timestamp).toInstant())
    } catch (_: DateTimeException) {
        timestamp
    }
}
