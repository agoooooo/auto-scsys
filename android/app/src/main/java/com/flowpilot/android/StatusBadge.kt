package com.flowpilot.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal data class StatusBadgeStyle(
    val foreground: Color,
    val background: Color,
)

internal fun runStatusBadgeStyle(status: String): StatusBadgeStyle = when (status) {
    "success", "online", "active", "published" -> StatusBadgeStyle(
        foreground = Color(0xFF238D6B),
        background = Color(0xFFEAF8F2),
    )
    "running", "pending", "awaiting_permission" -> StatusBadgeStyle(
        foreground = FlowPilotPalette.PurpleDark,
        background = FlowPilotPalette.PurpleSoft,
    )
    "offline" -> StatusBadgeStyle(
        foreground = Color(0xFFB78034),
        background = Color(0xFFFFF6E9),
    )
    "failure" -> StatusBadgeStyle(
        foreground = Color(0xFF929AAA),
        background = Color(0xFFF1F2F5),
    )
    else -> StatusBadgeStyle(
        foreground = Color(0xFF929AAA),
        background = Color(0xFFF1F2F5),
    )
}

@Composable
internal fun StatusBadge(label: String, status: String, modifier: Modifier = Modifier) {
    val style = runStatusBadgeStyle(status)
    Row(
        modifier = modifier
            .background(style.background, CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("•", color = style.foreground, style = MaterialTheme.typography.labelSmall)
        Text(
            label,
            modifier = Modifier.padding(start = 4.dp),
            color = style.foreground,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
