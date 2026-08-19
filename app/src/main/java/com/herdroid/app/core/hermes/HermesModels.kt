package com.herdroid.app.core.hermes

data class HermesGatewayConfig(
    val endpoint: String = "ws://127.0.0.1:8642/api/ws",
    val token: String = "",
)

enum class GatewayConnectionState {
    Idle,
    Connecting,
    Open,
    Closed,
    Error,
}

data class HermesEvent(
    val type: String,
    val sessionId: String?,
    val payload: String?,
)

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
) {
    enum class Role { User, Assistant, System }
}
