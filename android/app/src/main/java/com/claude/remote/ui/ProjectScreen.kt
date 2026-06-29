package com.claude.remote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.claude.remote.model.ProjectSessionInfo
import com.claude.remote.net.WsClient

/**
 * Browse a project folder's past Claude Code sessions (like the VS Code
 * extension / `claude --resume` picker). Enter a folder, list its sessions,
 * tap one to resume it, or start a fresh session in that folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    initialCwd: String,
    sessions: List<ProjectSessionInfo>,
    connState: WsClient.ConnState,
    onLoad: (cwd: String) -> Unit,
    onResume: (ProjectSessionInfo, cwd: String) -> Unit,
    onStartNew: (cwd: String) -> Unit,
    onDelete: (ProjectSessionInfo, cwd: String) -> Unit,
    onBack: () -> Unit,
) {
    var cwd by rememberSaveable { mutableStateOf(initialCwd) }
    var loading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectSessionInfo?>(null) }
    val trimmed = cwd.trim()
    val connected = connState == WsClient.ConnState.Connected
    val valid = trimmed.isNotEmpty() && connected

    // A response (even an empty list) clears the loading state.
    LaunchedEffect(sessions) { loading = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open project") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!connected) {
                Text(
                    "Not connected to the daemon (${connState.name.lowercase()}). " +
                        "Check the daemon is running and the VPN is on, then go Back and reconnect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFEF4444),
                )
            }

            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                label = { Text("Project folder (absolute path on the daemon host)") },
                placeholder = { Text("/home/you/code/project") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { loading = true; onLoad(trimmed) },
                    enabled = valid,
                ) { Text("List sessions") }
                OutlinedButton(onClick = { onStartNew(trimmed) }, enabled = valid) {
                    Text("Start new")
                }
            }

            HorizontalDivider()

            when {
                loading -> CircularProgressIndicator()
                sessions.isEmpty() -> Text(
                    "No sessions to show. Enter a folder and tap “List sessions”, "
                        + "or “Start new” to begin a fresh one.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(sessions, key = { it.id }) { s ->
                        SessionHistoryCard(
                            s = s,
                            onClick = { onResume(s, trimmed) },
                            onDelete = { pendingDelete = s },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete session?") },
            text = {
                Text(
                    "Permanently delete “${s.title.ifBlank { "(untitled session)" }}”? " +
                        "This removes its transcript on the daemon and can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    loading = true
                    onDelete(s, trimmed)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionHistoryCard(
    s: ProjectSessionInfo,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.title.ifBlank { "(untitled session)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    "${s.messages} messages · ${relativeTime(s.modified)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onDelete) { Text("🗑") }
        }
    }
}

private fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
