package edu.hse.agent.storage

import edu.hse.agent.models.AgentTask
import edu.hse.agent.models.BudgetLimits
import edu.hse.agent.models.BudgetState
import edu.hse.agent.models.BudgetUsage
import edu.hse.agent.models.CallRecord
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.Decision
import edu.hse.agent.models.Message
import edu.hse.agent.models.Mode
import edu.hse.agent.models.RunState
import edu.hse.agent.models.RunStatus
import edu.hse.agent.models.TaskStatus
import edu.hse.agent.models.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

class SqliteRunRepositoryTest {
    @TempDir lateinit var directory: Path

    private fun state(): RunState =
        RunState(
            workspace = "/workspace",
            policyPath = "/policy",
            mode = Mode.AUTO,
            budget = BudgetState(BudgetLimits(), BudgetUsage(1, 2, 1, 80)),
        )

    private fun execute(sql: String) {
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("agent.db")}").use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun count(table: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("agent.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    @Test
    fun `reopened database restores all state and relational components`() {
        val repository = SqliteRunRepository(directory)
        val state = state()
        val call = ToolCall("write", "write_file", "{}")
        state.status = RunStatus.NEEDS_RECOVERY
        state.tasks += AgentTask("change", "Change", emptyList(), listOf("write_file"), "write_file", TaskStatus.RUNNING)
        state.calls[call.id] = CallRecord(call, CallStatus.STARTED, attempts = 1, taskId = "change")
        state.messages += Message("user", "task")
        state.decisions += Decision("action", call.id, "approve", "user")
        repository.save(state)
        assertEquals(state, SqliteRunRepository(directory).load(state.runId))
        listOf("runs", "budgets", "tasks", "tool_calls", "messages", "decisions").forEach { assertEquals(1, count(it)) }
    }

    @Test
    fun `failed component insertion rolls back snapshot and budget together`() {
        val repository = SqliteRunRepository(directory)
        val before = state()
        repository.save(before)
        execute("CREATE TRIGGER reject_task BEFORE INSERT ON tasks WHEN NEW.id='bad' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        val changed = before.copy(budget = BudgetState(BudgetLimits(), BudgetUsage(10, 20, 5, 1000)), tasks = mutableListOf())
        changed.tasks += AgentTask("bad", "Fail insertion", emptyList(), listOf("read_file"), "read_file")
        assertThrows(SQLException::class.java) { repository.save(changed) }
        assertEquals(before, SqliteRunRepository(directory).load(before.runId))
        assertEquals(0, count("tasks"))
        DriverManager.getConnection("jdbc:sqlite:${repository.database}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT json_extract(usage_json, '$.modelRequests') FROM budgets").use { rows ->
                    rows.next()
                    assertEquals(1, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `failed first save leaves neither run nor components`() {
        val repository = SqliteRunRepository(directory)
        execute("CREATE TRIGGER reject_budget BEFORE INSERT ON budgets BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertThrows(SQLException::class.java) { repository.save(state()) }
        assertTrue(repository.listIds().isEmpty())
        assertEquals(0, count("runs"))
        assertEquals(0, count("budgets"))
    }

    @Test
    fun `legacy JSON is imported once and never overwrites newer SQLite state`() {
        val original = state()
        val legacy = JsonRunRepository(directory)
        legacy.save(original)
        val file = directory.resolve("${original.runId}.json")
        val bytes = Files.readString(file)
        val repository = SqliteRunRepository(directory)
        assertEquals(original, repository.load(original.runId))
        assertEquals(bytes, Files.readString(file))
        val updated = original.copy(budget = BudgetState(BudgetLimits(), BudgetUsage(7, 8, 3, 500)))
        repository.save(updated)
        assertEquals(updated, SqliteRunRepository(directory).load(original.runId))
        assertEquals(listOf(original.runId), repository.listIds())
        assertEquals(bytes, Files.readString(file))
    }

    @Test
    fun `corrupt legacy JSON cannot create partial database state`() {
        val repository = SqliteRunRepository(directory)
        val id = UUID.randomUUID().toString()
        Files.writeString(directory.resolve("$id.json"), "{corrupt")
        assertThrows(Exception::class.java) { repository.load(id) }
        assertEquals(0, count("runs"))
        assertEquals("{corrupt", Files.readString(directory.resolve("$id.json")))
    }

    @Test
    fun `updating one run preserves another run and its components`() {
        val repository = SqliteRunRepository(directory)
        val first = state()
        val second = state()
        first.messages += Message("user", "first")
        second.messages += Message("user", "second")
        repository.save(first)
        repository.save(second)
        first.messages.clear()
        repository.save(first)
        assertEquals(second, repository.load(second.runId))
        assertEquals(2, count("runs"))
        assertEquals(1, count("messages"))
    }

    @Test
    fun `same run lock excludes another instance and is released on failure`() {
        val repository = SqliteRunRepository(directory)
        val other = SqliteRunRepository(directory)
        val id = UUID.randomUUID().toString()
        assertThrows(IllegalStateException::class.java) {
            repository.locked(id) {
                assertThrows(Exception::class.java) { other.locked(id) { error("Must not run") } }
                error("Action failed")
            }
        }
        assertEquals("released", other.locked(id) { "released" })
    }

    @Test
    fun `unknown schema is rejected without downgrade`() {
        SqliteRunRepository(directory)
        execute("PRAGMA user_version=999")
        assertThrows(IllegalArgumentException::class.java) { SqliteRunRepository(directory) }
        DriverManager.getConnection("jdbc:sqlite:${directory.resolve("agent.db")}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { rows ->
                    rows.next()
                    assertEquals(999, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `invalid id cannot be used as a path or query`() {
        val repository = SqliteRunRepository(directory)
        assertThrows(IllegalArgumentException::class.java) { repository.load("../outside") }
        assertThrows(IllegalArgumentException::class.java) { repository.save(state().copy(runId = "' OR 1=1")) }
        assertTrue(repository.listIds().isEmpty())
    }
}
