package com.claude.remote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.claude.remote.data.ProjectConfig
import com.claude.remote.data.newId
import com.claude.remote.model.ProjectSessionInfo
import com.claude.remote.net.WsClient
import com.claude.remote.ui.components.EmptyState

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
    onBrowse: (cwd: String) -> Unit,
    onSaveProject: (ProjectConfig) -> Unit,
    onBack: () -> Unit,
) {
    // Re-seed when initialCwd changes, so returning from the folder browser with
    // a freshly-picked path updates the field.
    var cwd by rememberSaveable(initialCwd) { mutableStateOf(initialCwd) }
    var loading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ProjectSessionInfo?>(null) }
    var showSave by remember { mutableStateOf(false) }
    val trimmed = cwd.trim()
    val connected = connState == WsClient.ConnState.Connected
    val valid = trimmed.isNotEmpty() && connected

    // A response (even an empty list) clears the loading state.
    LaunchedEffect(sessions) { loading = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Open project") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showSave = true }, enabled = trimmed.isNotEmpty()) {
                        Text("Save as project")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!connected) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Not connected (${connState.name.lowercase()}). Check the daemon and VPN, " +
                                "then go Back and reconnect.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                label = { Text("Project folder") },
                supportingText = { Text("Absolute path on the daemon host, e.g. /home/you/code/project") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { onBrowse(trimmed) },
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Browse folders")
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { loading = true; onLoad(trimmed) },
                    enabled = valid,
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("List sessions")
                }
                OutlinedButton(onClick = { onStartNew(trimmed) }, enabled = valid) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Start new")
                }
            }

            HorizontalDivider()

            when {
                loading -> CircularProgressIndicator()
                sessions.isEmpty() -> EmptyState(
                    icon = Icons.Filled.History,
                    title = "No sessions to show",
                    subtitle = "Enter a folder and tap “List sessions”, or “Start new” to begin a fresh one.",
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

    if (showSave) {
        // Default the name to the folder's basename; the user can rename before saving.
        ProjectEditDialog(
            initial = ProjectConfig(
                id = newId(),
                name = trimmed.trimEnd('/').substringAfterLast('/'),
                path = trimmed,
            ),
            onSave = { onSaveProject(it); showSave = false },
            onDismiss = { showSave = false },
        )
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
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    s.title.ifBlank { "(untitled session)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                )
                Text(
                    "${s.messages} messages · ${relativeTime(s.modified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
