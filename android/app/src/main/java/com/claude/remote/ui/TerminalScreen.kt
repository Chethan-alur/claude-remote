package com.claude.remote.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * Terminal view for one session.
 *
 * Output is rendered by xterm.js inside a WebView (a real terminal emulator),
 * so claude's ANSI/TUI escape codes display correctly. The WebView is a
 * read-only renderer; input is handled by the native text field below it,
 * which sidesteps the soft-keyboard quirks of typing into a WebView.
 *
 * We feed only the *new* tail of the accumulated output buffer to xterm on each
 * update (xterm.write appends), tracking how much has been written.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    output: String,
    onBack: () -> Unit,
    onSendInput: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var written by remember { mutableStateOf(0) }

    fun submit() {
        if (draft.isNotEmpty()) {
            onSendInput(draft + "\n")
            draft = ""
        }
    }

    // Stream new output into xterm once the page has loaded.
    LaunchedEffect(output, ready) {
        val wv = webView
        if (!ready || wv == null) return@LaunchedEffect
        if (output.length < written) {
            // Buffer shrank (reconnect/cleared) — reset and replay from scratch.
            wv.evaluateJavascript("window.termReset && window.termReset();", null)
            written = 0
        }
        if (output.length > written) {
            val delta = output.substring(written)
            written = output.length
            wv.evaluateJavascript("window.termWrite(${JSONObject.quote(delta)});", null)
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                ready = true
                            }
                        }
                        loadUrl("file:///android_asset/term/term.html")
                        webView = this
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
