package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult
import java.nio.file.Path

data class PlanDecisionCommand(
    val runId: String,
    val stateDirectory: Path,
    val approved: Boolean,
    val reason: String,
    val crashAfter: String?,
) : Command {
    override fun execute(context: CommandContext): CommandResult {
        val runtime = context.createRuntime(stateDirectory, crashAfter)
        return CommandResult.State(runtime.planDecision(runId, approved, reason))
    }
}
