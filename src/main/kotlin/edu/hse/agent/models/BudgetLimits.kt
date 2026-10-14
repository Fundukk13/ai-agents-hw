package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class BudgetLimits(
    val modelRequests: Long = 30,
    val toolCalls: Long = 100,
    val sideEffects: Long = 20,
    val resultBytes: Long = 1_048_576,
) {
    init {
        require(listOf(modelRequests, toolCalls, sideEffects, resultBytes).all { it >= 0 }) { "Budget limits must be nonnegative" }
    }
}
