package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult
import edu.hse.agent.models.BudgetLimits
import edu.hse.agent.models.Mode
import edu.hse.agent.serialization.json
import java.nio.file.Files
import java.nio.file.Path

data class StartCommand(
    val workspace: Path,
    val policy: Path,
    val stateDirectory: Path,
    val mode: Mode,
    val task: String,
    val plan: Boolean,
    val crashAfter: String?,
    val budgetFile: Path? = null,
    val tasksEnabled: Boolean = false,
) : Command {
    override fun execute(context: CommandContext): CommandResult {
        val limits = budgetFile?.let { json.decodeFromString<BudgetLimits>(Files.readString(it)) } ?: BudgetLimits()
        val runtime = context.createRuntime(stateDirectory, crashAfter)
        return CommandResult.State(runtime.start(workspace, policy, mode, task, plan, limits, tasksEnabled))
    }
}
