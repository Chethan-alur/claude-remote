package com.claude.remote.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Terminal view for one session.
 *
 * Renders the accumulated PTY output as a scrolling monospace text block and
 * lets the user send a prompt back to Claude. ANSI escape codes are shown raw
 * for now; xterm.js rendering inside a WebView is deferred polish (see task 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    output: String,
    onBack: () -> Unit,
    onSendInput: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    // Keep the view pinned to the latest output as it streams in.
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }

    fun submit() {
        if (draft.isNotEmpty()) {
            onSendInput(draft + "\n")
            draft = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(sessionId, style = MaterialTheme.typography.titleSmall) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {
            Text(
                text = output.ifEmpty { "Attaching…" },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Send to Claude…") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { submit() }, enabled = draft.isNotEmpty()) {
                    Text("Send")
                }
            }
        }
    }
}
