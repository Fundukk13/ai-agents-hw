package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class BudgetState(
    val limits: BudgetLimits = BudgetLimits(),
    val used: BudgetUsage = BudgetUsage(),
)
