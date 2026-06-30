package com.claude.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claude.remote.discovery.DaemonBrowser

/**
 * First-launch flow:
 *   1. Discover daemons via mDNS (DaemonBrowser)
 *   2. User picks one
 *   3. Show 6-digit code field
 *   4. POST to <daemon>:<port>/pair with code; receive token
 *   5. Persist token + daemon address; navigate to SessionsScreen
 *
 * TODO(claude-code): wire this up. For Day 3 we can skip pairing and
 * just hardcode a token in BuildConfig.
 */
@Composable
fun PairingScreen(
    daemons: List<DaemonBrowser.Daemon>,
    onPair: (DaemonBrowser.Daemon, code: String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DaemonBrowser.Daemon?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pair with daemon", style = MaterialTheme.typography.headlineSmall)

        if (daemons.isEmpty()) {
            Text(
                "Searching for daemons on your network...",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            daemons.forEach { d ->
                Button(
                    onClick = { selected = d },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${d.name} — ${d.host}:${d.port}")
                }
            }
        }

        selected?.let { d ->
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("6-digit code") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onPair(d, code) },
                enabled = code.length == 6,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pair") }
        }
    }
}
