package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val reasoning: String? = null,
)
