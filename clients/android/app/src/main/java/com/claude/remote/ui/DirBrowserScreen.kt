package com.claude.remote.ui

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.SubdirectoryArrowLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.claude.remote.model.DirListing
import com.claude.remote.net.WsClient
import com.claude.remote.ui.components.EmptyState

/**
 * Browse the daemon host's folders to pick a project directory. Folders only —
 * the daemon never sends files (the app is a remote control, not a file
 * manager). Tapping a folder drills in; "Use this folder" selects the folder
 * currently shown. Hidden (dot) folders are filtered behind a toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirBrowserScreen(
    initialPath: String,
    listing: DirListing?,
    connState: WsClient.ConnState,
    onNavigate: (path: String) -> Unit,
    onUseFolder: (path: String) -> Unit,
    onBack: () -> Unit,
) {
    val connected = connState == WsClient.ConnState.Connected
    var showHidden by rememberSaveable { mutableStateOf(false) }
    // requestDir() clears the shared listing to null before each request, so a
    // null listing reliably means "a request is in flight" — including when we
    // revisit a folder whose contents are unchanged (a StateFlow would otherwise
    // dedupe the equal listing and never re-emit).
    val loading = listing == null

    // Load the starting folder once connected (and again if the link recovers).
    LaunchedEffect(connected) {
        if (connected) onNavigate(initialPath)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose a folder") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = { listing?.let { onUseFolder(it.path) } },
                        enabled = connected && listing != null && !loading,
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use this folder")
                    }
                }
            }
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
                            "Not connected (${connState.name.lowercase()}). Go Back and reconnect.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    listing?.path ?: initialPath.ifBlank { "~" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = showHidden,
                    onClick = { showHidden = !showHidden },
                    label = { Text("Hidden") },
                )
            }

            HorizontalDivider()

            val entries = listing?.entries.orEmpty()
                .filter { showHidden || !it.startsWith(".") }

            when {
                loading -> CircularProgressIndicator()
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // "Up" row, when not already at a filesystem root.
                    if (!listing?.parent.isNullOrEmpty()) {
                        item(key = "..") {
                            FolderRow(
                                name = "..",
                                icon = Icons.Filled.SubdirectoryArrowLeft,
                                onClick = { onNavigate(listing!!.parent) },
                            )
                        }
                    }
                    if (entries.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Filled.Folder,
                                title = "No sub-folders",
                                subtitle = "This folder has no sub-directories" +
                                    if (!showHidden) " (toggle “Hidden” to include dot-folders)." else ".",
                            )
                        }
                    }
                    items(entries, key = { it }) { name ->
                        FolderRow(
                            name = name,
                            icon = Icons.Filled.Folder,
                            onClick = { onNavigate(childPath(listing!!.path, name)) },
                        )
                    }
                }
            }
        }
    }
}

/** Join a parent dir and a child name into an absolute path, avoiding `//`. */
private fun childPath(parent: String, name: String): String =
    if (parent.endsWith("/")) "$parent$name" else "$parent/$name"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(
    name: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}
