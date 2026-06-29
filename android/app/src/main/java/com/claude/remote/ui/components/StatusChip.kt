package com.claude.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.claude.remote.net.WsClient
import com.claude.remote.ui.theme.StatusColors

/** Map a session status string (running/waiting/idle/dead) to a status color. */
fun sessionStatusColor(status: String): Color = when (status.lowercase()) {
    "running" -> StatusColors.positive
    "waiting" -> StatusColors.pending
    "idle" -> StatusColors.neutral
    "dead" -> StatusColors.danger
    else -> StatusColors.neutral
}

/** Map a connection state to (color, short label). */
fun connStateInfo(state: WsClient.ConnState): Pair<Color, String> = when (state) {
    WsClient.ConnState.Connected -> StatusColors.positive to "connected"
    WsClient.ConnState.Connecting -> StatusColors.pending to "connecting"
    WsClient.ConnState.Disconnected -> StatusColors.danger to "offline"
}

/**
 * A small status pill: a colored dot + label on a faint tinted background.
 * One implementation, reused for both session status and connection status,
 * replacing the dot/label blocks previously copy-pasted across screens.
 */
@Composable
fun StatusChip(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(7.dp).clip(CircleShape).background(color),
        )
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
