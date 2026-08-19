package com.herdroid.app.core.hermes

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface HermesRuntime {
    val state: StateFlow<RuntimeState>
    val events: SharedFlow<HermesEvent>

    suspend fun start(config: ProviderConfig)
    suspend fun submit(text: String, history: List<ChatMessage>)
    suspend fun stop()
}

enum class RuntimeState {
    Stopped,
    Starting,
    Ready,
    Busy,
    Error,
}

data class ProviderConfig(
    val baseUrl: String = "https://openrouter.ai/api/v1",
    val model: String = "",
    val apiKey: String = "",
    val maxIterations: Int = 8,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()
}
