package edu.hse.agent.cli

import edu.hse.agent.models.RunState
import edu.hse.agent.models.RunStatus
import edu.hse.agent.serialization.json

class ConsoleRenderer {
    fun render(result: CommandResult): Int =
        when (result) {
            is CommandResult.Text -> {
                println(result.text)
                0
            }

            is CommandResult.State -> {
                renderState(result.state)
            }
        }

    fun renderError(
        message: String?,
        exitCode: Int,
    ): Int {
        System.err.println(message)
        return exitCode
    }

    private fun renderState(state: RunState): Int {
        println("run_id=${state.runId} status=${state.status} phase=${state.phase}")
        println(BudgetRenderer().render(state.budget))
        if (state.tasks.isNotEmpty()) println(TaskRenderer().render(state.tasks))
        when (state.status) {
            RunStatus.WAITING_ACTION -> {
                val call = state.batch[state.batchIndex]
                println("Confirmation required: ${call.id} ${call.name} ${call.arguments}")
                println("Use approve or reject for this run_id.")
            }

            RunStatus.WAITING_PLAN -> {
                println(json.encodeToString(state.plan))
                println("Use approve-plan or reject-plan for this run_id.")
            }

            RunStatus.NEEDS_RECOVERY -> {
                println("Uncertain call: ${state.batch[state.batchIndex].id}. Inspect workspace, then recover --choice retry|fail|abort.")
            }

            RunStatus.COMPLETED -> {
                println(state.messages.lastOrNull { it.role == "assistant" }?.content)
            }

            else -> {
                Unit
            }
        }
        state.error?.let {
            System.err.println(it)
            return 1
        }
        if (state.status == RunStatus.FAILED) return 1
        return 0
    }
}
