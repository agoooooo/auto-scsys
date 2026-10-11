package com.flowpilot.android

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun formatRunStartTime(
    startedAtMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(zoneId)
    .format(Instant.ofEpochMilli(startedAtMillis))
