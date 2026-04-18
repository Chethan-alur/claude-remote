package com.claude.remote.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.claude.remote.BuildConfig
import com.claude.remote.model.Message
import com.claude.remote.model.PermissionRequest
import com.claude.remote.model.PermissionResponse
import com.claude.remote.net.WsClient
import com.claude.remote.notif.NotifBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

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
    private var client: WsClient? = null

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val messages: SharedFlow<Message> = _messages

    inner class LocalBinder : Binder() {
        val service: SessionService = this@SessionService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        notif = NotifBuilder(this)
        notif.ensureChannels()
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
            }
        }
        return START_STICKY
    }

    private fun connect() {
        if (client != null) return
        // POC: use BuildConfig defaults. Replace with stored daemon address + token after pairing.
        val url = "ws://${BuildConfig.DEFAULT_DAEMON_HOST}:${BuildConfig.DEFAULT_DAEMON_PORT}/"
        val token = "dev_placeholder" // TODO: load from DataStore after pairing
        val c = WsClient(url, token)
        client = c
        c.connect()

        collectJob = scope.launch {
            c.messages.collect { msg -> handleMessage(msg) }
        }
    }

    private fun handleMessage(msg: Message) {
        Log.d(tag, "msg: $msg")
        // Fan out to any UI observers.
        _messages.tryEmit(msg)

        when (msg) {
            is PermissionRequest -> notif.postPermissionRequest(msg)
            is com.claude.remote.model.Notification -> {
                if (msg.kind == "task_complete") {
                    notif.postTaskComplete(msg.session, msg.message)
                }
            }
            else -> { /* handled by UI */ }
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
    }
}
