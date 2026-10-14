package edu.hse.agent.cli.commands

import edu.hse.agent.cli.BudgetRenderer
import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult
import java.nio.file.Path

data class BudgetCommand(
    val runId: String,
    val stateDirectory: Path,
) : Command {
    override fun execute(context: CommandContext): CommandResult {
        val store = context.store(stateDirectory)
        val state = store.locked(runId) { store.load(runId) }
        return CommandResult.Text(BudgetRenderer().render(state.budget))
    }
}
