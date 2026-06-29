package com.claude.remote.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.claude.remote.data.DaemonConfig
import com.claude.remote.data.ProjectConfig
import com.claude.remote.data.newId
import com.claude.remote.model.SessionInfo
import com.claude.remote.net.WsClient
import com.claude.remote.ui.components.EmptyState
import com.claude.remote.ui.components.StatusChip
import com.claude.remote.ui.components.connStateInfo
import com.claude.remote.ui.components.sessionStatusColor

/**
 * Home for the selected daemon: its saved projects (pin / new-session / browse)
 * and its active sessions (open / rename / kill / clear-dead). Connection state
 * and reconnect/disconnect controls live in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaemonHomeScreen(
    daemon: DaemonConfig,
    sessions: List<SessionInfo>,
    connState: WsClient.ConnState,
    pathChecks: Map<String, Boolean>,
    handoffEnabled: Boolean,
    onSetHandoff: (Boolean) -> Unit,
    onOpenProject: (ProjectConfig) -> Unit,
    onNewSession: (ProjectConfig) -> Unit,
    onTogglePin: (ProjectConfig) -> Unit,
    onCheckPath: (String) -> Unit,
    onBrowsePath: () -> Unit,
    onSaveProject: (ProjectConfig) -> Unit,
    onDeleteProject: (projectId: String) -> Unit,
    onOpenSession: (SessionInfo) -> Unit,
    onRenameSession: (id: String, name: String) -> Unit,
    onKillSession: (id: String) -> Unit,
    onClearDead: () -> Unit,
    onSwitchDaemon: () -> Unit,
    onReconnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var editing by remember { mutableStateOf<ProjectConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionInfo?>(null) }

    // Validate saved project paths whenever connected / the set changes.
    LaunchedEffect(connState, daemon.projects) {
        if (connState == WsClient.ConnState.Connected) {
            daemon.projects.forEach { onCheckPath(it.path) }
        }
    }

    val sortedProjects = daemon.projects.sortedWith(
        compareByDescending<ProjectConfig> { it.pinned }.thenBy { it.name.lowercase() },
    )
    val deadCount = sessions.count { it.status == "dead" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(daemon.name) },
                navigationIcon = { TextButton(onClick = onSwitchDaemon) { Text("Daemons") } },
                actions = {
                    val (color, label) = connStateInfo(connState)
                    StatusChip(color, label)
                    if (connState == WsClient.ConnState.Connected) {
                        IconButton(onClick = onDisconnect) {
                            Icon(Icons.Filled.LinkOff, contentDescription = "Disconnect")
                        }
                    } else {
                        IconButton(onClick = onReconnect) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reconnect")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showDialog = true },
                text = { Text("Add project") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HandoffToggle(
                    enabled = handoffEnabled,
                    connected = connState == WsClient.ConnState.Connected,
                    onToggle = onSetHandoff,
                )
            }

            item { Text("Projects", style = MaterialTheme.typography.titleSmall) }
            if (daemon.projects.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Folder,
                        title = "No saved projects",
                        subtitle = "Add a folder path with the button below, or browse one manually.",
                    )
                }
            }
            items(sortedProjects, key = { it.id }) { p ->
                ProjectCard(
                    project = p,
                    pathMissing = pathChecks[p.path] == false,
                    onOpen = { onOpenProject(p) },
                    onNewSession = { onNewSession(p) },
                    onTogglePin = { onTogglePin(p) },
                    onEdit = { editing = p; showDialog = true },
                    onDelete = { onDeleteProject(p.id) },
                )
            }
            item {
                TextButton(onClick = onBrowsePath) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Browse a path manually…")
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Active sessions",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (deadCount > 0) {
                        TextButton(onClick = onClearDead) { Text("Clear dead ($deadCount)") }
                    }
                }
            }
            if (sessions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Terminal,
                        title = "No active sessions",
                        subtitle = "Open a project and start one, or resume a past session.",
                    )
                }
            }
            items(sessions, key = { it.id }) { s ->
                SessionCard(
                    session = s,
                    onOpen = { onOpenSession(s) },
                    onRename = { renaming = s },
                    onKill = { onKillSession(s.id) },
                )
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
    renaming?.let { s ->
        RenameSessionDialog(
            current = s.name,
            onSave = { onRenameSession(s.id, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

/**
 * Toggle for desktop->mobile handoff. While on, the daemon forwards permission
 * prompts from sessions it did not spawn (e.g. VSCode) to this phone, surfacing
 * them as "adopted" sessions. Disabled until connected.
 */
@Composable
private fun HandoffToggle(enabled: Boolean, connected: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Receive desktop permissions", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Forward Allow/Deny prompts from sessions started on the desktop " +
                        "(e.g. VSCode) to this phone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onToggle, enabled = connected)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectCard(
    project: ProjectConfig,
    pathMissing: Boolean,
    onOpen: () -> Unit,
    onNewSession: () -> Unit,
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(project.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = onTogglePin) {
                    Icon(
                        if (project.pinned) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (project.pinned) "Unpin" else "Pin",
                        tint = if (project.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                project.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp),
            )
            if (pathMissing) {
                Row(
                    modifier = Modifier.padding(start = 30.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Folder not found on the daemon host",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 22.dp)) {
                TextButton(onClick = onNewSession) {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New session")
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionCard(
    session: SessionInfo,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onKill: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val dead = session.status == "dead"
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(session.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (session.origin == "adopted") {
                    StatusChip(MaterialTheme.colorScheme.tertiary, "desktop")
                    Spacer(Modifier.width(6.dp))
                }
                StatusChip(sessionStatusColor(session.status), session.status)
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Session actions")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (dead) "Remove" else "Kill session") },
                            onClick = { menu = false; onKill() },
                        )
                    }
                }
            }
            Text(
                session.cwd,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp),
            )
            val detail = sessionDetail(session)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 30.dp, top = 2.dp),
                )
            }
        }
    }
}

/** "started 2h ago · active 5m ago" — omitted when the daemon sent no timestamps. */
private fun sessionDetail(s: SessionInfo): String? {
    if (s.startedAt <= 0L) return null
    val started = "started ${relativeTime(s.startedAt)}"
    return if (s.lastActivity > 0L) "$started · active ${relativeTime(s.lastActivity)}" else started
}

private fun relativeTime(epochSeconds: Long): String =
    DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000L,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/** Rename a live session's display name (stored on the phone). */
@Composable
private fun RenameSessionDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                supportingText = { Text("Shown on this phone only. Clear to restore the original.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
                    label = { Text("Folder") },
                    supportingText = { Text("Absolute path on the daemon host, e.g. /home/you/code/project") },
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
