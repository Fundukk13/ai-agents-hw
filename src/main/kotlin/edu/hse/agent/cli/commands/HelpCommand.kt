package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult
import edu.hse.agent.texts.TextResources

class HelpCommand : Command {
    override fun execute(context: CommandContext): CommandResult = CommandResult.Text(TextResources.read("cli/help.txt"))
}
