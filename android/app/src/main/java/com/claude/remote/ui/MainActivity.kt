package com.claude.remote.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.claude.remote.service.SessionService

/**
 * Hosts the Compose UI and binds to [SessionService].
 *
 * The service is the single source of truth (it outlives the activity, so
 * session state survives configuration changes and backgrounding). Once
 * bound, the UI observes its [SessionService.sessions] / [SessionService.conn]
 * / [SessionService.outputFor] flows and dispatches user actions back to it.
 */
class MainActivity : ComponentActivity() {

    // Observable so Compose recomposes the moment the service binds.
    private val service = mutableStateOf<SessionService?>(null)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result ignored — UI will show a hint if denied */ }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service.value = (binder as SessionService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName?) { service.value = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        startAndBindService()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val svc = service.value
                    if (svc == null) ConnectingPlaceholder() else AppRoot(svc)
                }
            }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, SessionService::class.java).apply {
            action = SessionService.ACTION_CONNECT
        }
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        unbindService(conn)
        super.onDestroy()
    }
}

@Composable
private fun ConnectingPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("Starting service…", modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun AppRoot(service: SessionService) {
    val sessions by service.sessions.collectAsState()
    val connState by service.conn.collectAsState()
    val projectSessions by service.projectSessions.collectAsState()
    val lastCreated by service.lastCreated.collectAsState()
    val lastError by service.lastError.collectAsState()

    // Surface daemon errors (e.g. bad cwd, session_creation_failed) as a toast.
    val ctx = LocalContext.current
    LaunchedEffect(lastError) {
        lastError?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            service.consumeError()
        }
    }

    // openId != null -> terminal; browseCwd != null -> project browser; else list.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var browseCwd by rememberSaveable { mutableStateOf<String?>(null) }
    var showDaemonDialog by remember { mutableStateOf(false) }

    // When a session is created or resumed, jump straight to its terminal.
    LaunchedEffect(lastCreated) {
        lastCreated?.let { id ->
            openId = id
            browseCwd = null
            service.consumeLastCreated()
        }
    }

    val open = openId
    when {
        open != null -> {
            val output by service.outputFor(open).collectAsState()
            // Subscribe to the session's output stream once when it is opened.
            LaunchedEffect(open) { service.attach(open) }
            BackHandler { openId = null }
            TerminalScreen(
                sessionId = open,
                output = output,
                onBack = { openId = null },
                onSendInput = { service.sendInput(open, it) },
            )
        }

        browseCwd != null -> {
            BackHandler { browseCwd = null }
            ProjectScreen(
                initialCwd = browseCwd ?: "",
                sessions = projectSessions,
                connState = connState,
                onLoad = { service.requestSessions(it) },
                onResume = { s, cwd ->
                    service.resumeSession(s.title.ifBlank { "session" }, cwd, s.id)
                },
                onStartNew = { cwd ->
                    service.createSession(cwd.substringAfterLast('/').ifBlank { "session" }, cwd)
                },
                onBack = { browseCwd = null },
            )
        }

        else -> SessionsScreen(
            sessions = sessions,
            connState = connState,
            onNewSession = { browseCwd = "" },
            onOpen = { openId = it.id },
            onSettings = { showDaemonDialog = true },
        )
    }

    if (showDaemonDialog) {
        DaemonDialog(
            initialHost = service.daemonHost,
            initialPort = service.daemonPort,
            onSave = { host, port ->
                service.updateDaemon(host, port)
                showDaemonDialog = false
            },
            onDismiss = { showDaemonDialog = false },
        )
    }
}

@Composable
private fun DaemonDialog(
    initialHost: String,
    initialPort: Int,
    onSave: (host: String, port: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    val portNum = port.toIntOrNull()
    val valid = host.isNotBlank() && portNum != null && portNum in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daemon address") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Host/IP the daemon is reachable at (e.g. your VPN or LAN address).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host / IP") },
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(host.trim(), portNum!!) }, enabled = valid) {
                Text("Save & reconnect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
