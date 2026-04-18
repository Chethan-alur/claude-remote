package com.claude.remote.net

import android.util.Log
import com.claude.remote.model.Message
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OkHttp WebSocket wrapper. Emits incoming Messages as a Flow.
 *
 * TODO(claude-code):
 *   - Add reconnect-with-backoff (start at 1s, cap at 30s)
 *   - Add 20s heartbeat ping
 *   - Surface connection state changes (Connecting / Connected / Disconnected) via [state]
 *   - Resume by sending session_attach with replay_bytes after reconnect
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

    private var ws: WebSocket? = null
    private val incoming = Channel<Message>(Channel.BUFFERED)

    private val _state = MutableStateFlow(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    val messages: Flow<Message> = incoming.receiveAsFlow()

    enum class ConnState { Disconnected, Connecting, Connected }

    fun connect() {
        _state.value = ConnState.Connecting
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(tag, "open")
                _state.value = ConnState.Connected
                // Send hello immediately.
                send(com.claude.remote.model.Hello(token))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = json.decodeFromString<Message>(text)
                    incoming.trySend(msg)
                } catch (e: Throwable) {
                    Log.w(tag, "decode failed: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "closing $code $reason")
                _state.value = ConnState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "failure", t)
                _state.value = ConnState.Disconnected
                // TODO: schedule reconnect
            }
        })
    }

    fun send(message: Message) {
        val payload = json.encodeToString(Message.serializer(), message)
        ws?.send(payload) ?: Log.w(tag, "send while disconnected: $payload")
    }

    fun close() {
        ws?.close(1000, "client closing")
        ws = null
    }
}
