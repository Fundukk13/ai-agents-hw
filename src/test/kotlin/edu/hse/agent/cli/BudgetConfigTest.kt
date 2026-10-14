package edu.hse.agent.cli

import edu.hse.agent.cli.commands.StartCommand
import edu.hse.agent.models.BudgetLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path

class BudgetConfigTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `budget path containing spaces is preserved`() {
        val path = directory.resolve("custom budget.json")
        val command = assertInstanceOf(StartCommand::class.java, CommandParser().parse(arrayOf("start", "--budget", path.toString())))
        assertEquals(path, command.budgetFile)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"modelRequests\":-1}",
            "{\"toolCalls\":-1}",
            "{\"sideEffects\":-1}",
            "{\"resultBytes\":-1}",
            "{\"modelRequests\":1.5}",
            "{\"unknownLimit\":10}",
            "{invalid",
        ],
    )
    fun `invalid budget config fails before creating model or changing workspace`(contents: String) {
        val path = directory.resolve("budget.json")
        Files.writeString(path, contents)
        val command = CommandParser().parse(arrayOf("start", "--budget", path.toString()))
        var environmentRead = false
        val context =
            CommandContext(environment = {
                environmentRead = true
                "test"
            })
        assertThrows(Exception::class.java) { command.execute(context) }
        assertEquals(false, environmentRead)
        assertEquals(contents, Files.readString(path))
    }

    @Test
    fun `missing budget file fails before model creation`() {
        val command = CommandParser().parse(arrayOf("start", "--budget", directory.resolve("missing.json").toString()))
        var reads = 0
        assertThrows(Exception::class.java) {
            command.execute(
                CommandContext(environment = {
                    reads++
                    "test"
                }),
            )
        }
        assertEquals(0, reads)
    }

    @Test
    fun `zero limits are permitted for explicit no-spend configuration`() {
        assertEquals(0L, BudgetLimits(0, 0, 0, 0).modelRequests)
    }
}
