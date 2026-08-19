package com.herdroid.app.core.hermes

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HermesGatewayClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    private val requestIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val mutableState = MutableStateFlow(GatewayConnectionState.Idle)
    private val mutableEvents = MutableSharedFlow<HermesEvent>(extraBufferCapacity = 128)

    val state: StateFlow<GatewayConnectionState> = mutableState
    val events: SharedFlow<HermesEvent> = mutableEvents

    @Volatile private var socket: WebSocket? = null

    fun connect(config: HermesGatewayConfig) {
        close()
        mutableState.value = GatewayConnectionState.Connecting
        val url = buildString {
            append(config.endpoint.trim())
            if (config.token.isNotBlank()) {
                append(if ('?' in config.endpoint) '&' else '?')
                append("token=")
                append(java.net.URLEncoder.encode(config.token, Charsets.UTF_8.name()))
            }
        }
        socket = httpClient.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun close() {
        socket?.close(1000, "HerDroid disconnect")
        socket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        if (mutableState.value != GatewayConnectionState.Idle) mutableState.value = GatewayConnectionState.Closed
    }

    suspend fun request(method: String, params: JSONObject = JSONObject()): JSONObject {
        val ws = socket ?: error("Hermes gateway is not connected")
        check(mutableState.value == GatewayConnectionState.Open) { "Hermes gateway is not open" }
        val id = requestIds.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val frame = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        if (!ws.send(frame.toString())) {
            pending.remove(id)
            error("Failed to send Hermes request")
        }
        return deferred.await()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { mutableState.value = GatewayConnectionState.Open }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = if (frame.has("id") && !frame.isNull("id")) frame.optLong("id") else null
            if (id != null) {
                val call = pending.remove(id) ?: return
                val error = frame.optJSONObject("error")
                if (error != null) call.completeExceptionally(IllegalStateException(error.optString("message", "Hermes RPC failed")))
                else {
                    val result = frame.opt("result")
                    call.complete(when (result) {
                        is JSONObject -> result
                        null, JSONObject.NULL -> JSONObject()
                        else -> JSONObject().put("value", result)
                    })
                }
                return
            }
            if (frame.optString("method") != "event") return
            val params = frame.optJSONObject("params") ?: return
            mutableEvents.tryEmit(HermesEvent(
                type = params.optString("type"),
                sessionId = params.optString("session_id").takeIf { it.isNotBlank() },
                payload = payloadToText(params.opt("payload")),
            ))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            mutableState.value = GatewayConnectionState.Closed
            failPending("Hermes gateway closed: $reason")
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            mutableState.value = GatewayConnectionState.Error
            failPending(t.message ?: "Hermes gateway connection failed")
            mutableEvents.tryEmit(HermesEvent("error", null, t.message))
        }
    }

    private fun failPending(message: String) {
        pending.values.forEach { it.completeExceptionally(IllegalStateException(message)) }
        pending.clear()
    }

    private fun payloadToText(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is JSONObject -> sequenceOf("text", "content", "message", "delta")
            .mapNotNull { key -> value.optString(key).takeIf { it.isNotBlank() } }
            .firstOrNull() ?: value.toString()
        else -> value.toString()
    }
}
