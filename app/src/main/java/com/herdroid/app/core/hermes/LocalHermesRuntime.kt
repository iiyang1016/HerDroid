package com.herdroid.app.core.hermes

import com.herdroid.app.core.terminal.TerminalController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class LocalHermesRuntime(
    private val filesDir: File,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) : HermesRuntime {
    private val mutableState = MutableStateFlow(RuntimeState.Stopped)
    private val mutableEvents = MutableSharedFlow<HermesEvent>(extraBufferCapacity = 128)
    private val terminal = TerminalController(filesDir)

    override val state: StateFlow<RuntimeState> = mutableState
    override val events: SharedFlow<HermesEvent> = mutableEvents

    @Volatile
    private var config: ProviderConfig? = null

    override suspend fun start(config: ProviderConfig) {
        mutableState.value = RuntimeState.Starting
        this.config = config.copy(
            baseUrl = config.baseUrl.trim().trimEnd('/'),
            model = config.model.trim(),
            apiKey = config.apiKey.trim(),
            maxIterations = config.maxIterations.coerceIn(1, 32),
        )
        mutableState.value = if (this.config?.isConfigured == true) RuntimeState.Ready else RuntimeState.Stopped
    }

    override suspend fun submit(text: String, history: List<ChatMessage>) {
        val activeConfig = config ?: error("Configure a model provider first")
        check(activeConfig.isConfigured) { "Configure a model provider first" }
        check(mutableState.value == RuntimeState.Ready) { "Local Hermes runtime is not ready" }

        mutableState.value = RuntimeState.Busy
        mutableEvents.emit(HermesEvent("message.start", null, null))

        runCatching {
            runAgentLoop(activeConfig, text, history)
        }.onSuccess { finalText ->
            mutableEvents.emit(HermesEvent("message.complete", null, finalText))
            mutableState.value = RuntimeState.Ready
        }.onFailure { error ->
            mutableState.value = RuntimeState.Error
            mutableEvents.emit(HermesEvent("error", null, error.message ?: "Local Hermes runtime failed"))
            mutableState.value = RuntimeState.Ready
        }
    }

    override suspend fun stop() {
        config = null
        mutableState.value = RuntimeState.Stopped
    }

    private suspend fun runAgentLoop(
        config: ProviderConfig,
        prompt: String,
        history: List<ChatMessage>,
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", SYSTEM_PROMPT),
            )

        history.takeLast(24).forEach { message ->
            if (message.streaming) return@forEach
            val role = when (message.role) {
                ChatMessage.Role.User -> "user"
                ChatMessage.Role.Assistant -> "assistant"
                ChatMessage.Role.System -> "system"
            }
            messages.put(JSONObject().put("role", role).put("content", message.text))
        }

        if (history.lastOrNull()?.role != ChatMessage.Role.User || history.lastOrNull()?.text != prompt) {
            messages.put(JSONObject().put("role", "user").put("content", prompt))
        }

        var finalText = ""

        repeat(config.maxIterations) { iteration ->
            mutableEvents.tryEmit(
                HermesEvent(
                    "status.update",
                    null,
                    "Local agent · step ${iteration + 1}/${config.maxIterations}",
                ),
            )

            val response = requestCompletion(config, messages)
            val choices = response.optJSONArray("choices") ?: error("Provider returned no choices")
            val message = choices.optJSONObject(0)?.optJSONObject("message")
                ?: error("Provider returned no assistant message")

            val content = message.optString("content").takeIf { it.isNotBlank() }.orEmpty()
            val toolCalls = message.optJSONArray("tool_calls")

            if (toolCalls == null || toolCalls.length() == 0) {
                finalText = content.ifBlank { "Hermes completed without a text response." }
                return@withContext finalText
            }

            val assistantEnvelope = JSONObject().put("role", "assistant")
            if (content.isNotBlank()) assistantEnvelope.put("content", content) else assistantEnvelope.put("content", JSONObject.NULL)
            assistantEnvelope.put("tool_calls", toolCalls)
            messages.put(assistantEnvelope)

            for (index in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(index) ?: continue
                val callId = call.optString("id")
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name")
                val arguments = runCatching { JSONObject(function.optString("arguments", "{}")) }
                    .getOrElse { JSONObject() }

                mutableEvents.tryEmit(HermesEvent("status.update", null, "Tool: $name"))
                val result = executeTool(name, arguments)

                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result),
                )
            }
        }

        if (finalText.isBlank()) {
            error("Hermes reached the local agent iteration limit (${config.maxIterations})")
        }
        finalText
    }

    private fun requestCompletion(config: ProviderConfig, messages: JSONArray): JSONObject {
        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("tools", TOOL_DEFINITIONS)
            .put("tool_choice", "auto")

        val requestBuilder = Request.Builder()
            .url(chatCompletionsUrl(config.baseUrl))
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val providerError = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()
                error(providerError?.takeIf { it.isNotBlank() } ?: "Provider HTTP ${response.code}: ${body.take(500)}")
            }
            return JSONObject(body)
        }
    }

    private suspend fun executeTool(name: String, arguments: JSONObject): String = when (name) {
        "shell_exec" -> {
            val command = arguments.optString("command").trim()
            if (command.isBlank()) {
                "shell_exec requires a non-empty command"
            } else {
                val result = terminal.execute(command)
                buildString {
                    append("exit_code=")
                    append(result.exitCode)
                    if (result.output.isNotBlank()) {
                        append('\n')
                        append(result.output.take(MAX_TOOL_OUTPUT))
                    }
                }
            }
        }
        else -> "Unknown tool: $name"
    }

    private fun chatCompletionsUrl(baseUrl: String): String {
        val clean = baseUrl.trim().trimEnd('/')
        return if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TOOL_OUTPUT = 16_000

        private const val SYSTEM_PROMPT = """
You are Hermes running locally inside HerDroid on Android.
You are an agent, not a remote gateway. Use the provided tools when they are useful.
The shell runs inside the HerDroid Android application sandbox through /system/bin/sh.
Be concise about tool execution, preserve user files, and never claim a tool ran unless a tool result was returned.
"""

        private val TOOL_DEFINITIONS = JSONArray().put(
            JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", "shell_exec")
                        .put("description", "Run a shell command inside the HerDroid app sandbox using /system/bin/sh.")
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject().put(
                                        "command",
                                        JSONObject()
                                            .put("type", "string")
                                            .put("description", "Shell command to execute."),
                                    ),
                                )
                                .put("required", JSONArray().put("command")),
                        ),
                ),
        )
    }
}
