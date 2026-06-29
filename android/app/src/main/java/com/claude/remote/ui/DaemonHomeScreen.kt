package com.claude.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.claude.remote.data.DaemonConfig
import com.claude.remote.data.ProjectConfig
import com.claude.remote.data.newId
import com.claude.remote.model.SessionInfo
import com.claude.remote.net.WsClient

/**
 * Home for the selected daemon: its saved projects (tap to open the
 * resume-or-new flow) and its active sessions (tap to open the terminal).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaemonHomeScreen(
    daemon: DaemonConfig,
    sessions: List<SessionInfo>,
    connState: WsClient.ConnState,
    onOpenProject: (ProjectConfig) -> Unit,
    onBrowsePath: () -> Unit,
    onSaveProject: (ProjectConfig) -> Unit,
    onDeleteProject: (projectId: String) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onSwitchDaemon: () -> Unit,
) {
    var editing by remember { mutableStateOf<ProjectConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(daemon.name) },
                navigationIcon = { TextButton(onClick = onSwitchDaemon) { Text("Daemons") } },
                actions = {
                    val (label, color) = when (connState) {
                        WsClient.ConnState.Connected -> "● connected" to Color(0xFF22C55E)
                        WsClient.ConnState.Connecting -> "● connecting" to Color(0xFFF59E0B)
                        WsClient.ConnState.Disconnected -> "● offline" to Color(0xFFEF4444)
                    }
                    Text(
                        label, color = color, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showDialog = true },
                text = { Text("Add project") },
                icon = { Text("+") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Projects", style = MaterialTheme.typography.titleSmall)
            }
            if (daemon.projects.isEmpty()) {
                item {
                    Text(
                        "No saved projects. Tap “Add project” to save a folder path, " +
                            "or browse one manually below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(daemon.projects, key = { it.id }) { p ->
                Card(onClick = { onOpenProject(p) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            p.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editing = p; showDialog = true }) { Text("Edit") }
                            TextButton(onClick = { onDeleteProject(p.id) }) { Text("Delete") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onBrowsePath) { Text("Browse a path manually…") }
            }

            item {
                Text(
                    "Active sessions",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (sessions.isEmpty()) {
                item {
                    Text(
                        "No active sessions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sessions, key = { it.id }) { s ->
                Card(onClick = { onOpenSession(s) }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            SessionStatusBadge(s.status)
                        }
                        Text(
                            s.cwd,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        ProjectEditDialog(
            initial = editing,
            onSave = { onSaveProject(it); showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun SessionStatusBadge(status: String) {
    val (label, color) = when (status) {
        "running" -> "running" to Color(0xFF3B82F6)
        "waiting" -> "waiting" to Color(0xFFF59E0B)
        "idle" -> "idle" to Color(0xFF9CA3AF)
        "dead" -> "dead" to Color(0xFFEF4444)
        else -> status to Color(0xFF9CA3AF)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
        Text(label, modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/** Add (initial == null) or edit a saved project. */
@Composable
fun ProjectEditDialog(
    initial: ProjectConfig?,
    onSave: (ProjectConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var path by remember { mutableStateOf(initial?.path ?: "") }
    val valid = name.isNotBlank() && path.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add project" else "Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Folder (absolute path on the daemon host)") },
                    placeholder = { Text("/home/you/code/project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = initial ?: ProjectConfig(id = newId(), name = "", path = "")
                    onSave(base.copy(name = name.trim(), path = path.trim()))
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
