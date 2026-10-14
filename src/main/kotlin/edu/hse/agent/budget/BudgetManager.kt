package edu.hse.agent.budget

import edu.hse.agent.models.CallRecord
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.RunState
import edu.hse.agent.models.ToolCall

class BudgetManager {
    fun isSideEffect(name: String): Boolean = name in setOf("write_file", "delete_file", "Bash")

    private fun check(
        name: String,
        used: Long,
        limit: Long,
        amount: Long,
    ) {
        if (amount > limit - used) throw BudgetExceeded("Budget exhausted: $name ($used/$limit used, $amount required)")
    }

    fun modelRequest(state: RunState) {
        check("model requests", state.budget.used.modelRequests, state.budget.limits.modelRequests, 1)
        state.budget.used.modelRequests++
        state.modelRequestPending = true
    }

    fun batch(
        state: RunState,
        calls: List<ToolCall>,
    ) {
        check("tool calls", state.budget.used.toolCalls, state.budget.limits.toolCalls, calls.size.toLong())
        val effects =
            calls.distinctBy { it.id }.count {
                isSideEffect(it.name) && state.calls[it.id]?.status != CallStatus.DONE
            }
        check("side effects for entire batch", state.budget.used.sideEffects, state.budget.limits.sideEffects, effects.toLong())
        check("result bytes for entire batch", state.budget.used.resultBytes, state.budget.limits.resultBytes, calls.size.toLong() * 18)
        state.budget.used.toolCalls += calls.size
    }

    fun attempt(
        state: RunState,
        record: CallRecord,
    ) {
        resultAllowance(state)
        if (record.retryPending) check("tool calls", state.budget.used.toolCalls, state.budget.limits.toolCalls, 1)
        if (isSideEffect(record.call.name)) check("side effects", state.budget.used.sideEffects, state.budget.limits.sideEffects, 1)
        if (record.retryPending) state.budget.used.toolCalls++
        if (isSideEffect(record.call.name)) state.budget.used.sideEffects++
        record.retryPending = false
    }

    fun resultAllowance(state: RunState): Int {
        val pending = (state.batch.size - state.batchIndex - 1).coerceAtLeast(0)
        val remaining = state.budget.limits.resultBytes - state.budget.used.resultBytes - pending.toLong() * 18
        if (remaining <
            18
        ) {
            throw BudgetExceeded(
                "Budget exhausted: result bytes (${state.budget.used.resultBytes}/${state.budget.limits.resultBytes} used)",
            )
        }
        return minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
    }
}
