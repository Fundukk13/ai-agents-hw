package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class BudgetUsage(
    var modelRequests: Long = 0,
    var toolCalls: Long = 0,
    var sideEffects: Long = 0,
    var resultBytes: Long = 0,
)
