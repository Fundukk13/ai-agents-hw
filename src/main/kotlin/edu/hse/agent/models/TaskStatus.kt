package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    BLOCKED,
}
