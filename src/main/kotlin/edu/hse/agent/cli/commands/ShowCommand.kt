package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult
import edu.hse.agent.serialization.json
import java.nio.file.Path

data class ShowCommand(
    val runId: String,
    val stateDirectory: Path,
) : Command {
    override fun execute(context: CommandContext): CommandResult {
        val store = context.store(stateDirectory)
        return CommandResult.Text(json.encodeToString(store.locked(runId) { store.load(runId) }))
    }
}
