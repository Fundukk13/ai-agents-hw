package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class TaskEvidence(
    val callId: String,
    val tool: String,
    val result: String,
)
