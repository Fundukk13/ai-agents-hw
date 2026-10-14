package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult

interface Command {
    fun execute(context: CommandContext): CommandResult
}
