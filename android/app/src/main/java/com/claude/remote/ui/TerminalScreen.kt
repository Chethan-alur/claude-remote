package com.claude.remote.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Terminal view. Hosts xterm.js inside a WebView and bridges it to the
 * SessionService.
 *
 * TODO(claude-code):
 *   - Bundle xterm.js as an asset under android/app/src/main/assets/term/
 *     (xterm.css, xterm.js, addon-fit, plus a tiny term.html that wires
 *     them together)
 *   - JS bridge: term sends keystrokes via [TerminalBridge.onInput],
 *     daemon output is fed in via webView.evaluateJavascript("term.write(${json})")
 *   - PermissionCard overlay when a permission_request arrives for this
 *     session. Use a Compose Card pinned above the WebView.
 *
 * For Day 3, a plain TextField + scrolling Text composable is fine — the
 * xterm.js work is polish, not function.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalScreen(
    sessionId: String,
    onSendInput: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    webViewClient = WebViewClient()
                    addJavascriptInterface(TerminalBridge(onSendInput), "AndroidBridge")
                    loadUrl("file:///android_asset/term/term.html")
                }
            },
        )

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { androidx.compose.material3.Text("Send to Claude...") },
            textStyle = MaterialTheme.typography.bodyMedium,
        )
    }
}

class TerminalBridge(private val onInput: (String) -> Unit) {
    @JavascriptInterface
    fun onInput(data: String) { onInput.invoke(data) }
}
