package com.claude.remote.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.claude.remote.net.WsClient
import com.claude.remote.ui.components.connStateInfo
import org.json.JSONObject

// Raw control sequences, built from code points so the source carries no
// fragile backslash escapes. ESC=0x1b, TAB=0x09, CR=0x0d, Ctrl-C=0x03.
private val ESC = 27.toChar().toString()
private val TAB = 9.toChar().toString()
private val CR = 13.toChar().toString()
private val CTRL_C = 3.toChar().toString()

// Cursor / navigation sequences (xterm "normal" mode). Shift-Tab is the CSI Z
// back-tab — Claude Code uses it to cycle permission mode.
private val UP = ESC + "[A"
private val DOWN = ESC + "[B"
private val RIGHT = ESC + "[C"
private val LEFT = ESC + "[D"
private val SHIFT_TAB = ESC + "[Z"

/**
 * Terminal view for one session.
 *
 * Output is rendered by xterm.js inside a WebView (a real terminal emulator),
 * so claude's ANSI/TUI escape codes display correctly. The terminal is
 * interactive: tapping it focuses xterm and opens the soft keyboard, and every
 * keystroke (chars, backspace, Enter, arrows — xterm emits the correct escape
 * sequences) is forwarded to the daemon via the AndroidInput JS bridge and
 * written to the PTY. A [KeyBar] supplies keys the soft keyboard lacks
 * (Esc/Tab/arrows/Enter) for driving claude's menus, and the compose field
 * below is a convenience for entering a longer prompt in one shot.
 *
 * We feed only the *new* tail of the accumulated output buffer to xterm on each
 * update (xterm.write appends), tracking how much has been written.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    sessionId: String,
    output: String,
    connState: WsClient.ConnState,
    uploadedPath: String?,
    onConsumeUploadedPath: () -> Unit,
    onAttachFile: () -> Unit,
    onBack: () -> Unit,
    onSendInput: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var written by remember { mutableStateOf(0) }
    // The control-key row is collapsed by default so the terminal gets the full
    // height; the header toggle expands it on demand.
    var keysExpanded by remember { mutableStateOf(false) }

    fun submit() {
        if (draft.isNotEmpty()) {
            // Terminal "Enter" is a carriage return (CR); claude's TUI treats a
            // lone newline as a literal newline in the input box, not a submit.
            onSendInput(draft + CR)
            draft = ""
        }
    }

    // When an upload completes, drop its saved path into the prompt draft so it
    // becomes part of the next message to Claude.
    LaunchedEffect(uploadedPath) {
        uploadedPath?.let { p ->
            draft = if (draft.isBlank()) p else "$draft $p"
            onConsumeUploadedPath()
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
                title = {
                    Text(
                        sessionId,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Live connection indicator so a dropped daemon is visible here.
                    val (dotColor, _) = connStateInfo(connState)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
                    Spacer(Modifier.width(4.dp))
                    // Expand/collapse the control-key row (Esc/Tab/arrows/…).
                    // Collapsed by default to leave the most room for the terminal.
                    IconButton(onClick = { keysExpanded = !keysExpanded }) {
                        Icon(
                            if (keysExpanded) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard,
                            contentDescription = "Toggle control keys",
                        )
                    }
                },
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
                        // Focusable so tapping the terminal opens the soft
                        // keyboard and routes keys into xterm's input.
                        isFocusable = true
                        isFocusableInTouchMode = true
                        // Bridge: xterm.onData(d) -> AndroidInput.send(d) -> PTY.
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun send(s: String) = onSendInput(s)
                            },
                            "AndroidInput",
                        )
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
            // Special keys the soft keyboard can't send — needed to drive
            // claude's interactive menus (arrow-select, Tab, Esc, submit).
            // Toggled from the header so it costs no terminal space when hidden.
            if (keysExpanded) {
                KeyBar(
                    onKey = onSendInput,
                    onFocusTerminal = {
                        webView?.evaluateJavascript("window.termFocus && window.termFocus();", null)
                    },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttachFile) {
                    Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Send to Claude…") },
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { submit() }, enabled = draft.isNotEmpty()) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (draft.isNotEmpty()) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

/** A horizontally scrollable row of control keys that emit raw PTY sequences. */
@Composable
private fun KeyBar(onKey: (String) -> Unit, onFocusTerminal: () -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Focus the terminal (raise the keyboard) without sending anything.
        KeyButton("⌨", onClick = onFocusTerminal)
        KeyButton("Esc", onClick = { onKey(ESC) })
        KeyButton("Tab", onClick = { onKey(TAB) })
        KeyButton("⇧Tab", onClick = { onKey(SHIFT_TAB) })
        KeyButton("↑", onClick = { onKey(UP) })
        KeyButton("↓", onClick = { onKey(DOWN) })
        KeyButton("←", onClick = { onKey(LEFT) })
        KeyButton("→", onClick = { onKey(RIGHT) })
        KeyButton("⏎", onClick = { onKey(CR) })
        KeyButton("^C", onClick = { onKey(CTRL_C) })
    }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        modifier = Modifier.padding(horizontal = 2.dp),
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    Spacer(Modifier.width(2.dp))
}
