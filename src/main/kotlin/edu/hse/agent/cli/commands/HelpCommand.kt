package edu.hse.agent.cli.commands

import edu.hse.agent.cli.CommandContext
import edu.hse.agent.cli.CommandResult

private val helpText =
    """
    Usage:
      ai-agents start [--task TEXT] [--mode safe|auto] [--plan] [--tasks]
                      [--workspace PATH] [--policy PATH] [--state-dir PATH] [--budget PATH]
      ai-agents tasks RUN_ID [--state-dir PATH]
      ai-agents budget RUN_ID [--state-dir PATH]
      ai-agents show RUN_ID [--state-dir PATH]
      ai-agents resume RUN_ID [--state-dir PATH]
      ai-agents approve|reject RUN_ID [--reason TEXT] [--state-dir PATH]
      ai-agents approve-plan|reject-plan RUN_ID [--reason TEXT] [--state-dir PATH]
      ai-agents recover RUN_ID --choice retry|fail|abort [--reason TEXT] [--state-dir PATH]
    Development: --crash-after TOOL injects a crash after execution, before result persistence.
    Default workspace: ./workspace; policy: ./policy.json; state: ./.runs; mode: safe.
    Real model environment: OPENAI_BASE_URL, OPENAI_API_KEY, OPENAI_MODEL.
    """.trimIndent()

class HelpCommand : Command {
    override fun execute(context: CommandContext): CommandResult = CommandResult.Text(helpText)
}
