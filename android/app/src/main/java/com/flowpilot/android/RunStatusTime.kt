package com.flowpilot.android

import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

internal fun formatRunStartTime(
    startedAtMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(startedAtMillis))

internal fun formatServerRunTime(
    timestamp: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (timestamp.isBlank()) return "时间未知"
    return try {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(zoneId)
            .format(OffsetDateTime.parse(timestamp).toInstant())
    } catch (_: DateTimeParseException) {
        timestamp
    }
}

internal fun formatEpochSeconds(
    timestampSeconds: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String? {
    if (timestampSeconds <= 0L) return null
    return try {
        formatRunStartTime(Math.multiplyExact(timestampSeconds, 1_000L), zoneId)
    } catch (_: ArithmeticException) {
        null
    } catch (_: DateTimeException) {
        null
    }
}
