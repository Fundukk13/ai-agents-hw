package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)
