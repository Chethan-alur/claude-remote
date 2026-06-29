package com.claude.remote.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.claude.remote.service.SessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    val connections by service.connections.collectAsState()
    val uploadedPath by service.uploadedPath.collectAsState()

    // Surface daemon errors (e.g. bad cwd, session_creation_failed) as a toast.
    val ctx = LocalContext.current
    LaunchedEffect(lastError) {
        lastError?.let {
            Toast.makeText(ctx, it, Toast.LENGTH_LONG).show()
            service.consumeError()
        }
    }

    // Navigation: openId -> terminal; browseCwd -> project browser; onConnections
    // (or no active daemon) -> connections list; else the active daemon's home.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var browseCwd by rememberSaveable { mutableStateOf<String?>(null) }
    var onConnections by rememberSaveable { mutableStateOf(true) }

    // When a session is created or resumed, jump straight to its terminal.
    LaunchedEffect(lastCreated) {
        lastCreated?.let { id ->
            openId = id
            browseCwd = null
            service.consumeLastCreated()
        }
    }

    val active = connections.daemons.firstOrNull { it.id == connections.activeDaemonId }
    val open = openId
    when {
        open != null -> {
            val output by service.outputFor(open).collectAsState()
            val scope = rememberCoroutineScope()
            // System document picker (SAF — no storage permission). Reads bytes
            // off the main thread and hands them to the service to upload.
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) scope.launch(Dispatchers.IO) {
                    val name = queryDisplayName(ctx, uri) ?: "upload.bin"
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) service.uploadFile(open, name, bytes)
                }
            }
            // Subscribe to the session's output stream once when it is opened.
            LaunchedEffect(open) { service.attach(open) }
            BackHandler { openId = null }
            TerminalScreen(
                sessionId = open,
                output = output,
                uploadedPath = uploadedPath,
                onConsumeUploadedPath = { service.consumeUploadedPath() },
                onAttachFile = { picker.launch(arrayOf("*/*")) },
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
                onDelete = { s, cwd -> service.deleteProjectSession(cwd, s.id) },
                onBack = { browseCwd = null },
            )
        }

        onConnections || active == null -> {
            val discovered = remember { service.discoverDaemons() }
            ConnectionsScreen(
                daemons = connections.daemons,
                activeDaemonId = connections.activeDaemonId,
                connState = connState,
                discovered = discovered,
                onSelect = { service.selectDaemon(it.id); onConnections = false },
                onSave = { service.saveDaemon(it) },
                onDelete = { service.deleteDaemon(it.id) },
            )
        }

        else -> active.let { daemon ->
            BackHandler { onConnections = true }
            DaemonHomeScreen(
                daemon = daemon,
                sessions = sessions,
                connState = connState,
                onOpenProject = { browseCwd = it.path },
                onBrowsePath = { browseCwd = "" },
                onSaveProject = { service.saveProject(daemon.id, it) },
                onDeleteProject = { service.deleteProject(daemon.id, it) },
                onOpenSession = { openId = it.id },
                onSwitchDaemon = { onConnections = true },
            )
        }
    }
}

/** Resolve a content Uri's display name via the OpenableColumns provider. */
private fun queryDisplayName(ctx: Context, uri: Uri): String? =
    ctx.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
