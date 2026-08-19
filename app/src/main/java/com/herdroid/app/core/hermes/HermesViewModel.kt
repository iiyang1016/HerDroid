package com.herdroid.app.core.hermes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.herdroid.app.core.runtime.BotModeController
import com.herdroid.app.core.runtime.HermesRuntimeHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

class HermesViewModel(application: Application) : AndroidViewModel(application) {
    private val configStore = ProviderConfigStore(application)
    private val runtime: HermesRuntime = HermesRuntimeHost.get(application)

    private val mutableUi = MutableStateFlow(
        HermesUiState(
            provider = configStore.load(),
            botModeEnabled = BotModeController.isEnabled(application),
        ),
    )
    val ui: StateFlow<HermesUiState> = mutableUi.asStateFlow()
    val runtimeState: StateFlow<RuntimeState> = runtime.state

    init {
        viewModelScope.launch {
            runtime.events.collect(::handleEvent)
        }
        viewModelScope.launch {
            val config = mutableUi.value.provider
            if (config.isConfigured && runtime.state.value == RuntimeState.Stopped) {
                runtime.start(config)
            }
        }
        if (mutableUi.value.botModeEnabled) {
            BotModeController.start(application)
        }
    }

    fun updateComposer(value: String) = mutableUi.update { it.copy(composer = value) }

    fun openProviderSettings() = mutableUi.update { it.copy(providerSettingsOpen = true) }
    fun closeProviderSettings() = mutableUi.update { it.copy(providerSettingsOpen = false) }

    fun updateProviderBaseUrl(value: String) = mutableUi.update {
        it.copy(provider = it.provider.copy(baseUrl = value))
    }

    fun updateProviderModel(value: String) = mutableUi.update {
        it.copy(provider = it.provider.copy(model = value))
    }

    fun updateProviderApiKey(value: String) = mutableUi.update {
        it.copy(provider = it.provider.copy(apiKey = value))
    }

    fun updateProviderMaxIterations(value: String) {
        val parsed = value.toIntOrNull()?.coerceIn(1, 32) ?: return
        mutableUi.update { it.copy(provider = it.provider.copy(maxIterations = parsed)) }
    }

    fun saveProvider() {
        val config = mutableUi.value.provider.copy(
            baseUrl = mutableUi.value.provider.baseUrl.trim().trimEnd('/'),
            model = mutableUi.value.provider.model.trim(),
            apiKey = mutableUi.value.provider.apiKey.trim(),
        )

        mutableUi.update {
            it.copy(
                provider = config,
                providerSettingsOpen = false,
                error = null,
            )
        }
        configStore.save(config)

        viewModelScope.launch {
            runCatching {
                runtime.stop()
                if (config.isConfigured) runtime.start(config)
                BotModeController.refresh(getApplication())
            }.onFailure { error ->
                mutableUi.update { it.copy(error = error.message ?: "Failed to start local Hermes runtime") }
            }
        }
    }

    fun setBotModeEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        BotModeController.setEnabled(app, enabled)
        mutableUi.update { it.copy(botModeEnabled = enabled) }
    }

    fun clearConversation() {
        mutableUi.update {
            it.copy(messages = emptyList(), status = null, error = null)
        }
    }

    fun send() {
        val text = ui.value.composer.trim()
        if (text.isEmpty()) return
        if (!ui.value.provider.isConfigured) {
            mutableUi.update { it.copy(providerSettingsOpen = true, error = "Configure a model provider first") }
            return
        }

        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatMessage.Role.User,
            text = text,
        )

        val history = ui.value.messages + userMessage
        mutableUi.update {
            it.copy(
                composer = "",
                error = null,
                messages = history,
            )
        }

        viewModelScope.launch {
            runCatching {
                runtime.submit(text, history)
            }.onFailure { error ->
                mutableUi.update { it.copy(error = error.message ?: "Prompt failed") }
            }
        }
    }

    private fun handleEvent(event: HermesEvent) {
        when (event.type) {
            "error" -> mutableUi.update {
                it.copy(error = event.payload ?: "Hermes error", status = null)
            }
            "message.start" -> ensureStreamingAssistant()
            "message.delta", "message.interim" -> appendAssistant(event.payload.orEmpty())
            "message.complete" -> finishAssistant(event.payload)
            "status.update" -> mutableUi.update { it.copy(status = event.payload) }
        }
    }

    private fun ensureStreamingAssistant() {
        mutableUi.update { state ->
            if (state.messages.lastOrNull()?.streaming == true) state
            else state.copy(
                messages = state.messages + ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatMessage.Role.Assistant,
                    text = "",
                    streaming = true,
                ),
            )
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
                if (finalPayload.isNullOrBlank()) {
                    state.copy(status = null)
                } else {
                    state.copy(
                        messages = state.messages + ChatMessage(
                            id = UUID.randomUUID().toString(),
                            role = ChatMessage.Role.Assistant,
                            text = finalPayload,
                        ),
                        status = null,
                    )
                }
            } else {
                val copy = state.messages.toMutableList()
                val current = copy[index]
                copy[index] = current.copy(
                    text = current.text.ifBlank { finalPayload.orEmpty() },
                    streaming = false,
                )
                state.copy(messages = copy, status = null)
            }
        }
    }
}

data class HermesUiState(
    val provider: ProviderConfig = ProviderConfig(),
    val providerSettingsOpen: Boolean = false,
    val botModeEnabled: Boolean = false,
    val composer: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val status: String? = null,
    val error: String? = null,
)
