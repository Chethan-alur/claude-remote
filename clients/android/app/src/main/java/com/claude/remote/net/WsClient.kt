package com.claude.remote.net

import android.util.Log
import com.claude.remote.model.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * OkHttp WebSocket wrapper. Emits incoming Messages as a Flow.
 *
 * Auto-reconnects with exponential backoff (1s → 30s, +jitter) whenever the
 * socket drops, unless [close] was called (a user-initiated disconnect). On each
 * (re)open it re-sends `hello`; the daemon then re-sends `welcome`, and the
 * service re-attaches the open session. A 20s OkHttp ping keeps NAT/idle alive.
 */
class WsClient(
    private val url: String,
    private val token: String,
) {
    private val tag = "WsClient"

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived
        .build()

    private val json = Json {
        classDiscriminator = "type"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var ws: WebSocket? = null
    private val incoming = Channel<Message>(Channel.BUFFERED)

    @Volatile private var userClosed = false
    private var attempt = 0
    private var reconnectJob: Job? = null

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    val messages: Flow<Message> = incoming.receiveAsFlow()

    enum class ConnState { Disconnected, Connecting, Connected }

    fun connect() {
        userClosed = false
        openSocket()
    }

    private fun openSocket() {
        _state.value = ConnState.Connecting
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "open")
                attempt = 0 // reset backoff on a successful connect
                _state.value = ConnState.Connected
                send(com.claude.remote.model.Hello(token))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    incoming.trySend(json.decodeFromString<Message>(text))
                } catch (e: Throwable) {
                    Log.w(tag, "decode failed: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "closing $code $reason")
                _state.value = ConnState.Disconnected
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "failure", t)
                _state.value = ConnState.Disconnected
                scheduleReconnect()
            }
        })
    }

    /** Back off 1s, 2s, 4s … capped at 30s, with up to 1s of jitter. */
    private fun scheduleReconnect() {
        if (userClosed) return
        val base = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        val wait = base + Random.nextLong(0, 1000)
        attempt++
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(wait)
            if (!userClosed) openSocket()
        }
    }

    fun send(message: Message) {
        val payload = json.encodeToString(Message.serializer(), message)
        ws?.send(payload) ?: Log.w(tag, "send while disconnected: $payload")
    }

    /** User-initiated disconnect: stop, and do NOT auto-reconnect. */
    fun close() {
        userClosed = true
        reconnectJob?.cancel()
        ws?.close(1000, "client closing")
        ws = null
    }
}
