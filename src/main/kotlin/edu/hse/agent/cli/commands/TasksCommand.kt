package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult
import edu.hse.agent.cli.TaskRenderer
import java.nio.file.Path

data class TasksCommand(
    val runId: String,
    val stateDirectory: Path,
) : Command {
    override fun execute(context: CommandContext): CommandResult {
        val store = context.store(stateDirectory)
        val state = store.locked(runId) { store.load(runId) }
        return CommandResult.Text(if (state.tasks.isEmpty()) "Задачи пока не созданы." else TaskRenderer().render(state.tasks))
    }
}
