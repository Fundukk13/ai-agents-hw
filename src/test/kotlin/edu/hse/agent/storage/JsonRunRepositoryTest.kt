package edu.hse.agent.storage

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
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class JsonRunRepositoryTest {
    @TempDir lateinit var directory: Path

    private fun state(): RunState = RunState(workspace = "/workspace", policyPath = "/policy.json", mode = Mode.SAFE)

    @Test
    fun `new store instance restores budget messages decisions and uncertain call`() {
        val store = JsonRunRepository(directory)
        val call = ToolCall("write", "write_file", "{\"path\":\"file.txt\",\"content\":\"text\"}")
        val state = state().copy(budget = BudgetState(BudgetLimits(), BudgetUsage(2, 4, 1, 120)))
        state.status = RunStatus.NEEDS_RECOVERY
        state.messages += Message("assistant", toolCalls = listOf(call), reasoning = "metadata")
        state.calls[call.id] = CallRecord(call, CallStatus.STARTED, attempts = 1)
        state.batch = listOf(call)
        state.decisions += Decision("action", call.id, "approve", "user approved")
        store.save(state)
        assertEquals(state, JsonRunRepository(directory).load(state.runId))
    }

    @ParameterizedTest
    @ValueSource(strings = ["../outside", "/tmp/outside", "not-a-uuid", "00000000-0000-0000-0000-000000000000.json"])
    fun `invalid ids cannot address arbitrary files`(id: String) {
        val store = JsonRunRepository(directory)
        assertThrows(IllegalArgumentException::class.java) { store.load(id) }
        assertThrows(IllegalArgumentException::class.java) { store.locked(id) { error("Must not run") } }
        assertThrows(IllegalArgumentException::class.java) { store.save(state().copy(runId = id)) }
        assertEquals(0L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `mismatched run id cannot restore another run`() {
        val store = JsonRunRepository(directory)
        val state = state()
        val requested = UUID.randomUUID().toString()
        Files.writeString(directory.resolve("$requested.json"), json.encodeToString(state))
        assertThrows(IllegalArgumentException::class.java) { store.load(requested) }
    }

    @Test
    fun `unsupported version is rejected`() {
        val store = JsonRunRepository(directory)
        val state = state().copy(version = 999)
        store.save(state)
        assertThrows(IllegalArgumentException::class.java) { store.load(state.runId) }
    }

    @Test
    fun `corrupt state is rejected without overwriting it`() {
        val store = JsonRunRepository(directory)
        val id = UUID.randomUUID().toString()
        val file = directory.resolve("$id.json")
        Files.writeString(file, "{corrupt")
        assertThrows(Exception::class.java) { store.load(id) }
        assertEquals("{corrupt", Files.readString(file))
    }

    @Test
    fun `overlapping lock rejects competing action and releases after failure`() {
        val store = JsonRunRepository(directory)
        val id = UUID.randomUUID().toString()
        assertThrows(IllegalStateException::class.java) {
            store.locked(id) {
                assertThrows(Exception::class.java) { JsonRunRepository(directory).locked(id) { error("Must not run") } }
                error("Action failed")
            }
        }
        assertEquals("available", JsonRunRepository(directory).locked(id) { "available" })
    }

    @Test
    fun `repeated saves replace complete state without leaving temporary files`() {
        val store = JsonRunRepository(directory)
        val state = state()
        store.save(state)
        state.messages += Message("user", "new history")
        state.budget.used.modelRequests = 3
        store.save(state)
        assertEquals(state, store.load(state.runId))
        assertFalse(Files.list(directory).use { paths -> paths.anyMatch { it.fileName.toString().startsWith(".state-") } })
    }
}
