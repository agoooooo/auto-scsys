package com.flowpilot.android

internal const val MAX_VISIBLE_RUN_HISTORY = 10

internal fun visibleRunHistory(records: List<RunRecord>): List<RunRecord> =
    records.take(MAX_VISIBLE_RUN_HISTORY)

internal fun isCurrentRunStatus(status: String?): Boolean =
    status == "running" || status == "awaiting_permission"
