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
import com.claude.remote.model.FileUpload
import com.claude.remote.model.FileUploaded
import com.claude.remote.model.Input
import com.claude.remote.model.DeleteSession
import com.claude.remote.model.ListSessions
import com.claude.remote.model.Message
import com.claude.remote.model.Output
import com.claude.remote.model.PermissionRequest
import com.claude.remote.model.PermissionResponse
import com.claude.remote.model.ProjectSessionInfo
import com.claude.remote.model.ProjectSessions
import com.claude.remote.model.SessionAttach
import com.claude.remote.model.SessionCreate
import com.claude.remote.model.SessionCreated
import com.claude.remote.model.SessionInfo
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
                    _conn.value = state
                    notif.updateStatus(buildStatusText(state))
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
        outputs.clear()
        connect()
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
            is Welcome -> _sessions.value = msg.sessions

            is SessionCreated -> {
                val info = SessionInfo(msg.id, msg.name, msg.cwd, status = "running")
                // Replace if a same-id entry already exists, else append.
                _sessions.value = _sessions.value.filterNot { it.id == msg.id } + info
                _lastCreated.value = msg.id
            }

            is ProjectSessions -> _projectSessions.value = msg.sessions

            is FileUploaded -> _uploadedPath.value = msg.path

            is Output -> {
                val buf = bufFor(msg.session)
                buf.value = buf.value + msg.data
            }

            is PermissionRequest -> {
                updateStatus(msg.session, "waiting")
                notif.postPermissionRequest(msg)
            }

            is com.claude.remote.model.Notification -> {
                if (msg.kind == "task_complete") {
                    updateStatus(msg.session, "idle")
                    notif.postTaskComplete(msg.session, msg.message)
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
        // Only ask for replay when we have no buffered output yet (first open or
        // after a reconnect clear). Re-attaching to a session we already hold the
        // scrollback for must NOT replay — handleMessage appends Output, so a
        // second replay would duplicate the entire terminal history.
        val replay = if (bufFor(sessionId).value.isEmpty()) 1_000_000 else 0
        client?.send(SessionAttach(sessionId, replayBytes = replay))
    }

    fun sendInput(sessionId: String, data: String) = client?.send(Input(sessionId, data))

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
