package edu.hse.agent.cli

import edu.hse.agent.cli.commands.ActionDecisionCommand
import edu.hse.agent.cli.commands.HelpCommand
import edu.hse.agent.cli.commands.PlanDecisionCommand
import edu.hse.agent.cli.commands.StartCommand
import edu.hse.agent.models.Mode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CommandParserTest {
    private val parser = CommandParser()

    @Test
    fun `start parses typed parameters and preserves task as one argument`() {
        val command =
            assertInstanceOf(
                StartCommand::class.java,
                parser.parse(
                    arrayOf("start", "--mode", "auto", "--task", "Fix order calculation", "--plan", "--workspace", "project"),
                ),
            )
        assertEquals(Mode.AUTO, command.mode)
        assertEquals("Fix order calculation", command.task)
        assertEquals(Path.of("project"), command.workspace)
        assertTrue(command.plan)
    }

    @Test
    fun `start defaults to safe`() {
        val command = assertInstanceOf(StartCommand::class.java, parser.parse(arrayOf("start")))
        assertEquals(Mode.SAFE, command.mode)
        assertFalse(command.plan)
    }

    @Test
    fun `rejections retain decision type and reason`() {
        val action =
            assertInstanceOf(
                ActionDecisionCommand::class.java,
                parser.parse(arrayOf("reject", "run-id", "--reason", "Do not write")),
            )
        assertFalse(action.approved)
        assertEquals("Do not write", action.reason)
        val plan = assertInstanceOf(PlanDecisionCommand::class.java, parser.parse(arrayOf("reject-plan", "run-id")))
        assertFalse(plan.approved)
    }

    @Test
    fun `invalid command arguments fail before creating runtime`() {
        val invalid =
            listOf(
                arrayOf("unknown"),
                arrayOf("start", "--demo"),
                arrayOf("start", "--mode", "fast"),
                arrayOf("start", "unexpected"),
                arrayOf("start", "--task"),
                arrayOf("start", "--mode", "safe", "--mode", "auto"),
                arrayOf("resume"),
                arrayOf("resume", "one", "two"),
                arrayOf("recover", "run-id"),
                arrayOf("recover", "run-id", "--choice", "ignore"),
            )
        invalid.forEach { args -> assertThrows(Exception::class.java) { parser.parse(args) } }
    }

    @Test
    fun `help executes without model configuration or state storage`() {
        val context = CommandContext(environment = { error("Environment must not be read") })
        val command = assertInstanceOf(HelpCommand::class.java, parser.parse(emptyArray()))
        val result = assertInstanceOf(CommandResult.Text::class.java, command.execute(context))
        assertTrue(result.text.contains("Usage:"))
    }
}
