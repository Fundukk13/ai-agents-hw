package edu.hse.agent.cli

import edu.hse.agent.models.BudgetState

class BudgetRenderer {
    fun render(budget: BudgetState): String {
        val used = budget.used
        val limits = budget.limits

        fun meter(
            name: String,
            spent: Long,
            limit: Long,
        ): String = "$name $spent/$limit (осталось ${limit - spent})"
        return "[budget] " +
            listOf(
                meter("Модель", used.modelRequests, limits.modelRequests),
                meter("Инструменты", used.toolCalls, limits.toolCalls),
                meter("Изменения/Bash", used.sideEffects, limits.sideEffects),
                meter("Результаты UTF-8, байт", used.resultBytes, limits.resultBytes),
            ).joinToString(" | ")
    }
}
