package com.claude.remote.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.claude.remote.data.DaemonConfig
import com.claude.remote.data.newId
import com.claude.remote.discovery.DaemonBrowser
import com.claude.remote.net.WsClient
import com.claude.remote.ui.components.EmptyState
import com.claude.remote.ui.components.StatusChip
import com.claude.remote.ui.components.connStateInfo
import kotlinx.coroutines.flow.Flow

/**
 * Top-level screen: pick and manage saved daemons/connections. Selecting one
 * makes it active and connects. Daemons discovered on the LAN via mDNS are
 * offered as quick-add suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    daemons: List<DaemonConfig>,
    activeDaemonId: String?,
    connState: WsClient.ConnState,
    discovered: Flow<DaemonBrowser.Daemon>,
    onSelect: (DaemonConfig) -> Unit,
    onSave: (DaemonConfig) -> Unit,
    onDelete: (DaemonConfig) -> Unit,
) {
    // null = dialog closed; otherwise editing this config (a fresh one for "add").
    var editing by remember { mutableStateOf<DaemonConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val found = remember { mutableStateListOf<DaemonBrowser.Daemon>() }
    LaunchedEffect(Unit) {
        discovered.collect { d ->
            if (found.none { it.host == d.host && it.port == d.port }) found.add(d)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Connections") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = null; showDialog = true },
                text = { Text("Add daemon") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (daemons.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Dns,
                        title = "No daemons yet",
                        subtitle = "Add one with the button below, or pick a daemon discovered on your network.",
                    )
                }
            }
            items(daemons, key = { it.id }) { d ->
                DaemonCard(
                    daemon = d,
                    active = d.id == activeDaemonId,
                    connState = connState,
                    onSelect = { onSelect(d) },
                    onEdit = { editing = d; showDialog = true },
                    onDelete = { onDelete(d) },
                )
            }

            val suggestions = found.filter { f -> daemons.none { it.host == f.host && it.port == f.port } }
            if (suggestions.isNotEmpty()) {
                item {
                    Text(
                        "Discovered on your network",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(suggestions, key = { "${it.host}:${it.port}" }) { f ->
                    Card(
                        onClick = {
                            editing = DaemonConfig(
                                id = newId(), name = f.name, host = f.host, port = f.port,
                            )
                            showDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(f.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${f.host}:${f.port} · tap to add",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        DaemonEditDialog(
            initial = editing,
            onSave = { onSave(it); showDialog = false },
            onDismiss = { showDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaemonCard(
    daemon: DaemonConfig,
    active: Boolean,
    connState: WsClient.ConnState,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        border = if (active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(daemon.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (active) {
                    val (color, label) = connStateInfo(connState)
                    StatusChip(color, label)
                }
            }
            Text(
                "${daemon.host}:${daemon.port} · ${daemon.projects.size} project(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
        }
    }
}

/** Add (initial == null) or edit a daemon. */
@Composable
fun DaemonEditDialog(
    initial: DaemonConfig?,
    onSave: (DaemonConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 8765).toString()) }
    var token by remember { mutableStateOf(initial?.token ?: "dev_placeholder") }
    val portNum = port.toIntOrNull()
    val valid = name.isNotBlank() && host.isNotBlank() && portNum != null && portNum in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add daemon" else "Edit daemon") },
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
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP") },
                    supportingText = { Text("IP or hostname of the machine running the daemon") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Device token") },
                    supportingText = { Text("Pairing token; keep the default for local dev") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = initial ?: DaemonConfig(id = newId(), name = "", host = "", port = 8765)
                    onSave(
                        base.copy(
                            name = name.trim(),
                            host = host.trim(),
                            port = portNum!!,
                            token = token.trim().ifBlank { "dev_placeholder" },
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
