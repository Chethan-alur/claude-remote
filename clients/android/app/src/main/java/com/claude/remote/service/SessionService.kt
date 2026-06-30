package com.claude.remote.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.claude.remote.data.ConnectionsState
import com.claude.remote.data.ConnectionsStore
import com.claude.remote.data.DaemonConfig
import com.claude.remote.data.ProjectConfig
import com.claude.remote.discovery.DaemonBrowser
import com.claude.remote.model.CheckPath
import com.claude.remote.model.DirListing
import com.claude.remote.model.FileUpload
import com.claude.remote.model.FileUploaded
import com.claude.remote.model.Input
import com.claude.remote.model.DeleteSession
import com.claude.remote.model.HandoffState
import com.claude.remote.model.KillSession
import com.claude.remote.model.ListDir
import com.claude.remote.model.ListSessions
import com.claude.remote.model.Message
import com.claude.remote.model.Output
import com.claude.remote.model.PathChecked
import com.claude.remote.model.PermissionRequest
import com.claude.remote.model.PermissionResolved
import com.claude.remote.model.PermissionResponse
import com.claude.remote.model.ProjectSessionInfo
import com.claude.remote.model.ProjectSessions
import com.claude.remote.model.Resize
import com.claude.remote.model.SessionAttach
import com.claude.remote.model.SessionCreate
import com.claude.remote.model.SessionCreated
import com.claude.remote.model.SessionInfo
import com.claude.remote.model.SessionsUpdate
import com.claude.remote.model.SetHandoff
import com.claude.remote.model.TakeOver
import com.claude.remote.model.Welcome
import com.claude.remote.net.WsClient
import com.claude.remote.notif.NotifBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service. Holds the WebSocket connection so it survives app
 * backgrounding. Routes incoming messages to:
 *   - the UI (via [messages] SharedFlow), if anything is observing
 *   - the notification system, for permission requests and task completes
 *
 * Bound by MainActivity for direct send/observe; also receives ACTION_*
 * intents from PermissionActionReceiver for lock-screen button taps.
 *
 * TODO(claude-code):
 *   - Persist token + daemon address (DataStore) and read on start
 *   - Reconnect on network change
 *   - Per-session state tracking
 */
class SessionService : Service() {
    private val tag = "SessionService"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var collectJob: Job? = null

    private lateinit var notif: NotifBuilder
    private lateinit var store: ConnectionsStore
    private val browser by lazy { DaemonBrowser(this) }
    private var client: WsClient? = null

    // The session whose terminal is currently open, so we can re-attach it on
    // reconnect. Set by [attach]; survives a drop.
    @Volatile private var openSessionId: String? = null

    // Saved daemons + projects + which one is active. Persisted via [store].
    private val _connections = MutableStateFlow(ConnectionsState())
    val connections: StateFlow<ConnectionsState> = _connections.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages

    // Known sessions, sourced from `welcome` and `session_created` frames.
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    // Connection state, mirrored from the active WsClient.
    private val _conn = MutableStateFlow(WsClient.ConnState.Disconnected)
    val conn: StateFlow<WsClient.ConnState> = _conn.asStateFlow()

    // Desktop->mobile handoff state, sourced from `welcome` and `handoff_state`.
    private val _handoffEnabled = MutableStateFlow(false)
    val handoffEnabled: StateFlow<Boolean> = _handoffEnabled.asStateFlow()

    // Per-session accumulated terminal output. Keyed by session id.
    // ConcurrentHashMap because the IO collector writes while the UI thread reads.
    private val outputs = ConcurrentHashMap<String, MutableStateFlow<String>>()

    private fun bufFor(sessionId: String): MutableStateFlow<String> =
        outputs.computeIfAbsent(sessionId) { MutableStateFlow("") }

    /** Observable output buffer for a session (created lazily). */
    fun outputFor(sessionId: String): StateFlow<String> = bufFor(sessionId).asStateFlow()

    // Past Claude sessions for the project folder currently being browsed.
    private val _projectSessions = MutableStateFlow<List<ProjectSessionInfo>>(emptyList())
    val projectSessions: StateFlow<List<ProjectSessionInfo>> = _projectSessions.asStateFlow()

    // Results of check_path requests, keyed by the requested path → is-a-dir.
    private val _pathChecks = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val pathChecks: StateFlow<Map<String, Boolean>> = _pathChecks.asStateFlow()

    // Most recent dir_listing response, for the folder browser. Null until the
    // first list_dir reply arrives.
    private val _dirListing = MutableStateFlow<DirListing?>(null)
    val dirListing: StateFlow<DirListing?> = _dirListing.asStateFlow()

    // Id of the most recently created/resumed session, so the UI can navigate
    // to its terminal once `session_created` arrives. Cleared by consumeLastCreated().
    private val _lastCreated = MutableStateFlow<String?>(null)
    val lastCreated: StateFlow<String?> = _lastCreated.asStateFlow()
    fun consumeLastCreated() { _lastCreated.value = null }

    // Last error frame from the daemon, surfaced to the UI as a toast.
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    fun consumeError() { _lastError.value = null }

    // Saved path of the most recent completed upload, for the terminal to insert
    // into the prompt draft. Cleared by consumeUploadedPath().
    private val _uploadedPath = MutableStateFlow<String?>(null)
    val uploadedPath: StateFlow<String?> = _uploadedPath.asStateFlow()
    fun consumeUploadedPath() { _uploadedPath.value = null }

    inner class LocalBinder : Binder() {
        val service: SessionService = this@SessionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notif = NotifBuilder(this)
        notif.ensureChannels()
        store = ConnectionsStore(this)
        _connections.value = store.load()
        startForeground(NotifBuilder.STATUS_NOTIF_ID, notif.buildStatus("Connecting..."))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_PERMISSION_RESPONSE -> {
                val id = intent.getStringExtra(EXTRA_REQ_ID) ?: return START_STICKY
                val decision = intent.getStringExtra(EXTRA_DECISION) ?: return START_STICKY
                client?.send(PermissionResponse(id = id, decision = decision))
                notif.cancelPermission(id)
                // "No, and tell Claude what to do instead": when the reply carries
                // freeform guidance, deny the tool and forward the text as the
                // session's next prompt (submitted with CR, like the terminal).
                val session = intent.getStringExtra(EXTRA_SESSION)
                val message = intent.getStringExtra(EXTRA_MESSAGE)
                if (!session.isNullOrEmpty() && !message.isNullOrBlank()) {
                    client?.send(Input(session, message + "\r"))
                }
            }
        }
        return START_STICKY
    }

    private fun activeDaemon(): DaemonConfig? =
        _connections.value.daemons.firstOrNull { it.id == _connections.value.activeDaemonId }

    private fun connect() {
        if (client != null) return
        val d = activeDaemon() ?: run {
            _conn.value = WsClient.ConnState.Disconnected
            notif.updateStatus("No daemon selected")
            return
        }
        val url = "ws://${d.host}:${d.port}/"
        val c = WsClient(url, d.token)
        client = c
        c.connect()

        // One parent job owns both collectors so reconnect() can cancel them together.
        collectJob = scope.launch {
            launch { c.messages.collect { msg -> handleMessage(msg) } }
            launch {
                c.state.collect { state ->
                    val prev = _conn.value
                    _conn.value = state
                    notif.updateStatus(buildStatusText(state))
                    // On a (re)connect, if a terminal is open, re-attach it with a
                    // full replay so the screen repaints after the gap. Clearing
                    // the buffer first makes TerminalScreen reset before the replay.
                    if (state == WsClient.ConnState.Connected &&
                        prev != WsClient.ConnState.Connected
                    ) {
                        openSessionId?.let { sid ->
                            bufFor(sid).value = ""
                            client?.send(SessionAttach(sid, replayBytes = 1_000_000))
                        }
                    }
                }
            }
        }
    }

    private fun reconnect() {
        collectJob?.cancel()
        client?.close()
        client = null
        // Drop state from the previous daemon so it can't leak into the new view.
        _sessions.value = emptyList()
        _projectSessions.value = emptyList()
        _dirListing.value = null
        outputs.clear()
        connect()
    }

    /** Manual "Reconnect" — tear down and reconnect to the active daemon. */
    fun reconnectNow() = reconnect()

    /** Manual "Disconnect" — stop and stay offline (no auto-retry) until reconnected. */
    fun disconnect() {
        collectJob?.cancel()
        client?.close() // userClosed -> WsClient won't auto-retry
        client = null
        _conn.value = WsClient.ConnState.Disconnected
        notif.updateStatus("Disconnected")
    }

    // --- daemon/project management (persist + update flow + reconnect as needed) ---

    /** Make a daemon active and connect to it. */
    fun selectDaemon(daemonId: String) {
        _connections.value = store.setActive(daemonId)
        reconnect()
    }

    /** Insert or update a daemon; reconnect if the active one changed. */
    fun saveDaemon(d: DaemonConfig) {
        _connections.value = store.upsertDaemon(d)
        if (d.id == _connections.value.activeDaemonId) reconnect()
    }

    /** Delete a daemon; if it was active, drop the connection. */
    fun deleteDaemon(id: String) {
        val wasActive = id == _connections.value.activeDaemonId
        _connections.value = store.deleteDaemon(id)
        if (wasActive) reconnect()  // activeDaemon() is now null -> lands Disconnected
    }

    fun saveProject(daemonId: String, p: ProjectConfig) {
        _connections.value = store.upsertProject(daemonId, p)
    }

    fun deleteProject(daemonId: String, projectId: String) {
        _connections.value = store.deleteProject(daemonId, projectId)
    }

    /** mDNS-discovered daemons on the LAN, for quick-add suggestions. */
    fun discoverDaemons(): Flow<DaemonBrowser.Daemon> = browser.browse()

    private fun buildStatusText(state: WsClient.ConnState): String = when (state) {
        WsClient.ConnState.Connected -> "Connected"
        WsClient.ConnState.Connecting -> "Connecting…"
        WsClient.ConnState.Disconnected -> "Disconnected"
    }

    private fun handleMessage(msg: Message) {
        Log.d(tag, "msg: $msg")
        // Fan out to any UI observers.
        _messages.tryEmit(msg)

        when (msg) {
            is Welcome -> {
                _sessions.value = applyNames(msg.sessions)
                _handoffEnabled.value = msg.handoffEnabled
            }

            is HandoffState -> _handoffEnabled.value = msg.enabled

            is SessionsUpdate -> _sessions.value = applyNames(msg.sessions)

            is SessionCreated -> {
                val info = SessionInfo(msg.id, msg.name, msg.cwd, status = "running")
                // Replace if a same-id entry already exists, else append.
                _sessions.value = applyNames(_sessions.value.filterNot { it.id == msg.id } + info)
                _lastCreated.value = msg.id
            }

            is ProjectSessions -> _projectSessions.value = msg.sessions

            is PathChecked -> _pathChecks.value = _pathChecks.value + (msg.path to msg.isDir)

            is DirListing -> _dirListing.value = msg

            is FileUploaded -> _uploadedPath.value = msg.path

            is Output -> {
                val buf = bufFor(msg.session)
                buf.value = buf.value + msg.data
            }

            is PermissionRequest -> {
                updateStatus(msg.session, "waiting")
                notif.postPermissionRequest(msg)
            }

            is PermissionResolved -> {
                // Another client answered first, or the request expired and fell
                // through to the local prompt — dismiss our now-stale notification.
                notif.cancelPermission(msg.id)
            }

            is com.claude.remote.model.Notification -> {
                when (msg.kind) {
                    "task_complete" -> {
                        updateStatus(msg.session, "idle")
                        notif.postTaskComplete(msg.session, msg.message)
                    }
                    // Surface a warning (e.g. takeover could not stop the desktop
                    // session) via the app's transient message channel.
                    "warning", "error" -> _lastError.value = msg.message
                }
            }

            is com.claude.remote.model.Error -> {
                _lastError.value = "${msg.code}: ${msg.message}"
            }

            else -> { /* handled by UI */ }
        }
    }

    /** Patch one session's status in place; the protocol has no standalone status frame. */
    private fun updateStatus(sessionId: String, status: String) {
        _sessions.value = _sessions.value.map {
            if (it.id == sessionId) it.copy(status = status) else it
        }
    }

    // --- actions invoked by the UI ---

    fun createSession(name: String, cwd: String) = client?.send(SessionCreate(name, cwd))

    /** Resume a past Claude session (claude --resume <resumeId>) in its folder. */
    fun resumeSession(name: String, cwd: String, resumeId: String) =
        client?.send(SessionCreate(name = name, cwd = cwd, resumeId = resumeId))

    /** Ask the daemon for the past sessions stored for a project folder. */
    fun requestSessions(cwd: String) {
        _projectSessions.value = emptyList()
        client?.send(ListSessions(cwd))
    }

    /** Delete a past session's transcript; daemon replies with the refreshed list. */
    fun deleteProjectSession(cwd: String, id: String) =
        client?.send(DeleteSession(cwd, id))

    fun attach(sessionId: String) {
        // Remember which terminal is open so a reconnect can re-attach it.
        openSessionId = sessionId
        // Only ask for replay when we have no buffered output yet (first open or
        // after a reconnect clear). Re-attaching to a session we already hold the
        // scrollback for must NOT replay — handleMessage appends Output, so a
        // second replay would duplicate the entire terminal history.
        val replay = if (bufFor(sessionId).value.isEmpty()) 1_000_000 else 0
        client?.send(SessionAttach(sessionId, replayBytes = replay))
    }

    fun sendInput(sessionId: String, data: String) = client?.send(Input(sessionId, data))

    /** Tell the daemon to resize the PTY so claude reflows to the phone's width. */
    fun resizePty(sessionId: String, cols: Int, rows: Int) =
        client?.send(Resize(sessionId, cols, rows))

    /** Terminate a live session on the daemon (daemon pushes a sessions_update). */
    fun killSession(id: String) = client?.send(KillSession(id))

    /**
     * Take over an adopted (desktop) session: the daemon stops the desktop
     * claude and resumes the conversation as a daemon-owned session we can
     * drive. The session then arrives as origin "spawned" via sessions_update.
     */
    fun takeOver(id: String) = client?.send(TakeOver(id))

    /** Remove all exited (dead) sessions from the daemon's registry. */
    fun clearDeadSessions() {
        _sessions.value.filter { it.status == "dead" }.forEach { client?.send(KillSession(it.id)) }
    }

    /** Ask the daemon whether a folder exists on the host; result lands in [pathChecks]. */
    fun checkPath(path: String) = client?.send(CheckPath(path))

    /**
     * Ask the daemon to list a folder's sub-directories for the picker; the
     * reply lands in [dirListing]. A blank path starts at the daemon's home.
     *
     * Clearing [dirListing] to null first makes a null value reliably mean "a
     * request is in flight" — the browser's loading signal. Without this, a
     * StateFlow would dedupe an unchanged listing (e.g. revisiting a folder),
     * leaving the spinner stuck because no new value is emitted.
     */
    fun requestDir(path: String) {
        _dirListing.value = null
        client?.send(ListDir(path))
    }

    /**
     * Toggle desktop->mobile handoff. When enabled, the daemon forwards
     * permission prompts from sessions it did not spawn (e.g. VSCode) to this
     * phone. The daemon echoes a `handoff_state`, which updates [handoffEnabled].
     */
    fun setHandoff(enabled: Boolean) = client?.send(SetHandoff(enabled))

    /** Set (or clear, when blank) a session's local display name. */
    fun renameSession(id: String, name: String) {
        _connections.value = store.setSessionName(id, name)
        _sessions.value = applyNames(_sessions.value)
    }

    /** Overlay local display-name overrides on a daemon-provided session list. */
    private fun applyNames(list: List<SessionInfo>): List<SessionInfo> {
        val names = _connections.value.sessionNames
        if (names.isEmpty()) return list
        return list.map { s -> names[s.id]?.let { s.copy(name = it) } ?: s }
    }

    /**
     * Upload a file to the session's project on the daemon. Reads/encodes off
     * the main thread and chunks at 256 KB raw to bound each WS frame. The
     * daemon replies with `file_uploaded`; its path lands in [uploadedPath].
     */
    fun uploadFile(sessionId: String, filename: String, bytes: ByteArray) {
        val c = client ?: run {
            _lastError.value = "Not connected — can't upload"
            return
        }
        scope.launch {
            val uploadId = UUID.randomUUID().toString()
            val chunk = 256 * 1024
            if (bytes.isEmpty()) {
                c.send(FileUpload(sessionId, filename, uploadId, 0, 1, ""))
                return@launch
            }
            val total = (bytes.size + chunk - 1) / chunk
            var seq = 0
            var off = 0
            while (off < bytes.size) {
                val end = minOf(off + chunk, bytes.size)
                val b64 = Base64.encodeToString(bytes.copyOfRange(off, end), Base64.NO_WRAP)
                c.send(FileUpload(sessionId, filename, uploadId, seq, total, b64))
                off = end
                seq++
            }
        }
    }

    fun send(msg: Message) { client?.send(msg) }

    override fun onDestroy() {
        collectJob?.cancel()
        client?.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.claude.remote.CONNECT"
        const val ACTION_PERMISSION_RESPONSE = "com.claude.remote.PERMISSION_RESPONSE"
        const val EXTRA_REQ_ID = "req_id"
        const val EXTRA_DECISION = "decision"
        const val EXTRA_SESSION = "session"
        const val EXTRA_MESSAGE = "message"
    }
}
