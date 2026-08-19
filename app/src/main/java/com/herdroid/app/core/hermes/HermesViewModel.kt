package com.herdroid.app.core.hermes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

class HermesViewModel : ViewModel() {
    private val gateway = HermesGatewayClient()
    private val mutableUi = MutableStateFlow(HermesUiState())
    val ui: StateFlow<HermesUiState> = mutableUi.asStateFlow()
    val connectionState: StateFlow<GatewayConnectionState> = gateway.state
    private var runtimeSessionId: String? = null

    init { viewModelScope.launch { gateway.events.collect { handleEvent(it) } } }

    fun updateEndpoint(value: String) = mutableUi.update { it.copy(endpoint = value) }
    fun updateToken(value: String) = mutableUi.update { it.copy(token = value) }
    fun updateComposer(value: String) = mutableUi.update { it.copy(composer = value) }

    fun connect() {
        runtimeSessionId = null
        mutableUi.update { it.copy(error = null) }
        gateway.connect(HermesGatewayConfig(ui.value.endpoint, ui.value.token))
    }

    fun disconnect() { runtimeSessionId = null; gateway.close() }

    fun send() {
        val text = ui.value.composer.trim()
        if (text.isEmpty()) return
        mutableUi.update { it.copy(
            composer = "",
            error = null,
            messages = it.messages + ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.User, text),
        ) }
        viewModelScope.launch {
            runCatching {
                val sessionId = ensureSession()
                gateway.request("prompt.submit", JSONObject().put("session_id", sessionId).put("text", text))
            }.onFailure { e -> mutableUi.update { it.copy(error = e.message ?: "Prompt failed") } }
        }
    }

    private suspend fun ensureSession(): String {
        runtimeSessionId?.let { return it }
        val created = gateway.request("session.create", JSONObject().put("cols", 100).put("source", "android").put("title", "HerDroid"))
        return created.optString("session_id").takeIf { it.isNotBlank() }?.also { runtimeSessionId = it }
            ?: error("Hermes returned no session_id")
    }

    private fun handleEvent(event: HermesEvent) {
        if (event.type == "error") {
            mutableUi.update { it.copy(error = event.payload ?: "Hermes error") }
            return
        }
        if (event.sessionId != null && runtimeSessionId != null && event.sessionId != runtimeSessionId) return
        when (event.type) {
            "message.start" -> ensureStreamingAssistant()
            "message.delta", "message.interim" -> appendAssistant(event.payload.orEmpty())
            "message.complete" -> finishAssistant(event.payload)
            "status.update" -> mutableUi.update { it.copy(status = event.payload) }
        }
    }

    private fun ensureStreamingAssistant() {
        mutableUi.update { state ->
            if (state.messages.lastOrNull()?.streaming == true) state
            else state.copy(messages = state.messages + ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.Assistant, "", true))
        }
    }

    private fun appendAssistant(delta: String) {
        if (delta.isEmpty()) return
        ensureStreamingAssistant()
        mutableUi.update { state ->
            val index = state.messages.indexOfLast { it.streaming }
            if (index == -1) return@update state
            val copy = state.messages.toMutableList()
            copy[index] = copy[index].copy(text = copy[index].text + delta)
            state.copy(messages = copy)
        }
    }

    private fun finishAssistant(finalPayload: String?) {
        mutableUi.update { state ->
            val index = state.messages.indexOfLast { it.streaming }
            if (index == -1) {
                if (finalPayload.isNullOrBlank()) state
                else state.copy(messages = state.messages + ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.Assistant, finalPayload))
            } else {
                val copy = state.messages.toMutableList()
                val current = copy[index]
                copy[index] = current.copy(text = current.text.ifBlank { finalPayload.orEmpty() }, streaming = false)
                state.copy(messages = copy, status = null)
            }
        }
    }

    override fun onCleared() { gateway.close(); super.onCleared() }
}

data class HermesUiState(
    val endpoint: String = "ws://127.0.0.1:8642/api/ws",
    val token: String = "",
    val composer: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val status: String? = null,
    val error: String? = null,
)
