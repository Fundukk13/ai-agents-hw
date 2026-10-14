package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class AgentTask(
    val id: String,
    val description: String,
    val dependencies: List<String>,
    val tools: List<String>,
    val verificationTool: String,
    var status: TaskStatus = TaskStatus.PENDING,
    var result: TaskEvidence? = null,
)
