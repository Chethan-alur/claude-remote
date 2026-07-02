package com.claude.remote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.claude.remote.model.HistoryMessage
import com.claude.remote.ui.components.EmptyState
import androidx.compose.material.icons.filled.Forum

/**
 * Scrollable conversation history for a session, read from Claude's on-disk
 * transcript. Claude's TUI runs in the terminal's alternate-screen buffer, which
 * keeps no scrollback, so earlier turns can't be recovered from the live
 * terminal — this view fills that gap. Read-only: to reply, go back to the
 * terminal. `messages == null` means the transcript is still loading.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    sessionName: String,
    messages: List<HistoryMessage>?,
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    // Open on the newest message: jump to the last item once the transcript
    // loads (or grows). Non-animated so the view never flashes the oldest turn.
    LaunchedEffect(messages?.size) {
        val count = messages?.size ?: 0
        if (count > 0) listState.scrollToItem(count - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History · $sessionName", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to terminal")
                    }
                },
            )
        },
    ) { padding ->
        when {
            messages == null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            messages.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Forum,
                    title = "No conversation history",
                    subtitle = "This session's transcript has no messages yet.",
                )
            }

            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(messages) { _, m -> MessageBubble(m) }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: HistoryMessage) {
    val isUser = m.role == "user"
    val bg = if (isUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            MessageHeader(
                label = if (isUser) "You" else "Claude",
                ts = m.ts,
                color = fg.copy(alpha = 0.7f),
            )
            Text(
                m.text,
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun MessageHeader(label: String, ts: Long, color: androidx.compose.ui.graphics.Color) {
    val time = if (ts > 0) " · " + DateUtils.getRelativeTimeSpanString(
        ts * 1000L, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ) else ""
    Text(
        "$label$time",
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.SansSerif),
        color = color,
    )
}
