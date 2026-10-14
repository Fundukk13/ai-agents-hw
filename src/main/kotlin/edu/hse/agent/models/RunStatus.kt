package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
enum class RunStatus {
    RUNNING,
    WAITING_ACTION,
    WAITING_PLAN,
    NEEDS_RECOVERY,
    COMPLETED,
    FAILED,
    BUDGET_EXHAUSTED,
}
