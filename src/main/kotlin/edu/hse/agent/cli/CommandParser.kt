package edu.hse.agent.cli

import edu.hse.agent.cli.commands.ActionDecisionCommand
import edu.hse.agent.cli.commands.BudgetCommand
import edu.hse.agent.cli.commands.Command
import edu.hse.agent.cli.commands.HelpCommand
import edu.hse.agent.cli.commands.PlanDecisionCommand
import edu.hse.agent.cli.commands.RecoverCommand
import edu.hse.agent.cli.commands.ResumeCommand
import edu.hse.agent.cli.commands.ShowCommand
import edu.hse.agent.cli.commands.StartCommand
import edu.hse.agent.cli.commands.TasksCommand
import edu.hse.agent.models.Mode
import java.nio.file.Path

private const val DEFAULT_TASK =
    "Исправь расчёт итоговой стоимости заказа, чтобы тесты проходили. Тесты не меняй. " +
        "Сам найди нужные файлы, внеси исправление и запусти тесты."

class CommandParser {
    fun parse(args: Array<String>): Command {
        val input = CliArguments(args)
        val directory = Path.of(input.options["state-dir"] ?: ".runs")
        val crashAfter = input.options["crash-after"]
        val reason = input.options["reason"] ?: "Explicit user CLI decision"
        return when (input.command) {
            "help", "--help", "-h" -> {
                HelpCommand()
            }

            "start" -> {
                parseStart(input, directory, crashAfter)
            }

            "tasks" -> {
                TasksCommand(input.id(), directory)
            }

            "budget" -> {
                BudgetCommand(input.id(), directory)
            }

            "show" -> {
                ShowCommand(input.id(), directory)
            }

            "resume" -> {
                ResumeCommand(input.id(), directory, crashAfter)
            }

            "approve", "reject" -> {
                ActionDecisionCommand(input.id(), directory, input.command == "approve", reason, crashAfter)
            }

            "approve-plan", "reject-plan" -> {
                PlanDecisionCommand(input.id(), directory, input.command == "approve-plan", reason, crashAfter)
            }

            "recover" -> {
                val choice = input.options["choice"] ?: error("--choice is required")
                require(choice in setOf("retry", "fail", "abort")) { "Choice must be retry, fail or abort" }
                RecoverCommand(input.id(), directory, choice, reason, crashAfter)
            }

            else -> {
                error("Unknown command")
            }
        }
    }

    private fun parseStart(
        input: CliArguments,
        directory: Path,
        crashAfter: String?,
    ): StartCommand {
        require(input.positional.isEmpty()) { "Use --task for task text" }
        val mode =
            when (input.options["mode"] ?: "safe") {
                "safe" -> Mode.SAFE
                "auto" -> Mode.AUTO
                else -> error("Mode must be safe or auto")
            }
        return StartCommand(
            Path.of(input.options["workspace"] ?: "workspace"),
            Path.of(input.options["policy"] ?: "policy.json"),
            directory,
            mode,
            input.options["task"] ?: DEFAULT_TASK,
            "plan" in input.options,
            crashAfter,
            input.options["budget"]?.let(Path::of),
            "tasks" in input.options,
        )
    }
}
