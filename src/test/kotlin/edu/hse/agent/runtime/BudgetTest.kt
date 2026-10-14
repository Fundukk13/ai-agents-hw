package edu.hse.agent.runtime

import edu.hse.agent.llm.ModelClient
import edu.hse.agent.models.BudgetLimits
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.Message
import edu.hse.agent.models.Mode
import edu.hse.agent.models.PolicyConfig
import edu.hse.agent.models.Rules
import edu.hse.agent.models.RunState
import edu.hse.agent.models.RunStatus
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import edu.hse.agent.storage.RunRepository
import edu.hse.agent.storage.SqliteRunRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BudgetTest {
    @TempDir lateinit var directory: Path

    private lateinit var workspace: Path
    private lateinit var policy: Path
    private lateinit var store: RunRepository

    private fun setup() {
        workspace = Files.createDirectory(directory.resolve("workspace"))
        Files.writeString(workspace.resolve("data.txt"), "Привет 🌍 ".repeat(1000))
        policy = directory.resolve("policy.json")
        Files.writeString(
            policy,
            json.encodeToString(PolicyConfig(Rules(listOf("echo hello"), emptyList()), Rules(listOf("."), emptyList()))),
        )
        store = SqliteRunRepository(directory.resolve("runs"))
    }

    private fun call(
        id: String,
        name: String,
        vararg arguments: Pair<String, String>,
    ) = ToolCall(id, name, json.encodeToString(JsonObject(arguments.associate { it.first to JsonPrimitive(it.second) })))

    private fun reply(vararg calls: ToolCall) = Message("assistant", toolCalls = calls.toList())

    private fun model(vararg replies: Message): ModelClient {
        val queue = ArrayDeque(replies.toList())
        return ModelClient { _, _ ->
            check(queue.isNotEmpty())
            queue.removeFirst()
        }
    }

    private fun start(
        runtime: AgentLoop,
        limits: BudgetLimits,
        mode: Mode = Mode.AUTO,
        plan: Boolean = false,
    ): RunState = runtime.start(workspace, policy, mode, "test task", plan, limits)

    @Test
    fun `zero model budget prevents any request and resume cannot reset it`() {
        setup()
        val forbidden = ModelClient { _, _ -> error("Must not request model") }
        val state = start(AgentLoop(store, forbidden), BudgetLimits(modelRequests = 0))
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        assertEquals(0L, state.budget.used.modelRequests)
        assertEquals(state.budget, AgentLoop(store, forbidden).drive(state.runId).budget)
    }

    @Test
    fun `transport failure is charged and resume respects model limit`() {
        setup()
        val failing = ModelClient { _, _ -> error("Network failure") }
        val state = start(AgentLoop(store, failing), BudgetLimits(modelRequests = 1))
        assertEquals(RunStatus.RUNNING, state.status)
        assertEquals(1L, state.budget.used.modelRequests)
        val resumed = AgentLoop(store, ModelClient { _, _ -> error("Must not retry") }).drive(state.runId)
        assertEquals(RunStatus.BUDGET_EXHAUSTED, resumed.status)
        assertEquals(1L, resumed.budget.used.modelRequests)
    }

    @Test
    fun `oversized tool batch executes none including its reads`() {
        setup()
        val state =
            start(
                AgentLoop(
                    store,
                    model(
                        reply(
                            call("read", "read_file", "path" to "data.txt"),
                            call("write", "write_file", "path" to "new.txt", "content" to "changed"),
                        ),
                    ),
                ),
                BudgetLimits(toolCalls = 1),
            )
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        assertTrue(state.calls.isEmpty())
        assertEquals(0L, state.budget.used.toolCalls)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }

    @Test
    fun `insufficient side effect budget rejects whole batch`() {
        setup()
        val state =
            start(
                AgentLoop(
                    store,
                    model(
                        reply(
                            call("read", "read_file", "path" to "data.txt"),
                            call("write", "write_file", "path" to "new.txt", "content" to "changed"),
                            call("bash", "Bash", "command" to "echo hello", "cwd" to "."),
                        ),
                    ),
                ),
                BudgetLimits(sideEffects = 1),
            )
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        assertTrue(state.calls.isEmpty())
        assertEquals(0L, state.budget.used.sideEffects)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }

    @Test
    fun `UTF-8 results are capped and marked incomplete then next tool stops`() {
        setup()
        val state =
            start(
                AgentLoop(
                    store,
                    model(
                        reply(
                            call("read", "read_file", "path" to "data.txt"),
                        ),
                        reply(call("write", "write_file", "path" to "new.txt", "content" to "changed")),
                    ),
                ),
                BudgetLimits(resultBytes = 18),
            )
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        val result = state.calls.getValue("read").result!!
        assertEquals(18, result.toByteArray(Charsets.UTF_8).size)
        assertTrue(
            json
                .parseToJsonElement(result)
                .jsonObject
                .getValue("truncated")
                .jsonPrimitive.boolean,
        )
        assertEquals(18L, state.budget.used.resultBytes)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }

    @Test
    fun `insufficient result space rejects whole batch`() {
        setup()
        val state =
            start(
                AgentLoop(
                    store,
                    model(
                        reply(
                            call("read", "read_file", "path" to "data.txt"),
                            call("write", "write_file", "path" to "new.txt", "content" to "changed"),
                        ),
                    ),
                ),
                BudgetLimits(resultBytes = 18),
            )
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        assertTrue(state.calls.isEmpty())
        assertEquals(0L, state.budget.used.resultBytes)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }

    @Test
    fun `large first result leaves room for all remaining batch results`() {
        setup()
        val state =
            start(
                AgentLoop(
                    store,
                    model(
                        reply(
                            call("read", "read_file", "path" to "data.txt"),
                            call("write", "write_file", "path" to "new.txt", "content" to "changed"),
                        ),
                        Message("assistant", "done"),
                    ),
                ),
                BudgetLimits(resultBytes = 36),
            )
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(36L, state.budget.used.resultBytes)
        assertEquals("changed", Files.readString(workspace.resolve("new.txt")))
    }

    @Test
    fun `zero result allowance prevents tool execution`() {
        setup()
        val state =
            start(
                AgentLoop(store, model(reply(call("write", "write_file", "path" to "new.txt", "content" to "changed")))),
                BudgetLimits(resultBytes = 0),
            )
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }

    @Test
    fun `Bash respects remaining output bytes`() {
        setup()
        val state =
            start(
                AgentLoop(store, model(reply(call("bash", "Bash", "command" to "echo hello", "cwd" to ".")), Message("assistant", "done"))),
                BudgetLimits(resultBytes = 18),
            )
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(1L, state.budget.used.sideEffects)
        assertEquals(18L, state.budget.used.resultBytes)
        assertTrue(
            json
                .parseToJsonElement(state.calls.getValue("bash").result!!)
                .jsonObject
                .getValue("truncated")
                .jsonPrimitive.boolean,
        )
    }

    @Test
    fun `crash after debit retains counters and requires explicit recovery before retry`() {
        setup()
        val write = call("write", "write_file", "path" to "new.txt", "content" to "changed")
        val runtime = AgentLoop(store, model(reply(write)), afterBudgetDebit = { if (it == "write_file") throw SimulatedCrash() })
        assertThrows(SimulatedCrash::class.java) { start(runtime, BudgetLimits(toolCalls = 2, sideEffects = 2)) }
        val id =
            store.listIds().single()
        val saved = store.load(id)
        assertEquals(1L, saved.budget.used.modelRequests)
        assertEquals(1L, saved.budget.used.toolCalls)
        assertEquals(1L, saved.budget.used.sideEffects)
        assertEquals(CallStatus.STARTED, saved.calls.getValue("write").status)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
        val resumed = AgentLoop(store, model()).drive(id)
        assertEquals(RunStatus.NEEDS_RECOVERY, resumed.status)
        assertEquals(saved.budget, resumed.budget)
        val recovered = AgentLoop(store, model(Message("assistant", "done"))).recover(id, "retry", "Explicit retry")
        assertEquals(RunStatus.COMPLETED, recovered.status)
        assertEquals(2L, recovered.budget.used.toolCalls)
        assertEquals(2L, recovered.budget.used.sideEffects)
        assertEquals(2, recovered.calls.getValue("write").attempts)
    }

    @Test
    fun `crash before model call does not refund its debit`() {
        setup()
        val runtime = AgentLoop(store, model(), afterBudgetDebit = { throw SimulatedCrash() })
        assertThrows(SimulatedCrash::class.java) { start(runtime, BudgetLimits(modelRequests = 1)) }
        val id =
            store.listIds().single()
        assertTrue(store.load(id).modelRequestPending)
        assertEquals(RunStatus.BUDGET_EXHAUSTED, AgentLoop(store, model()).drive(id).status)
        assertEquals(
            1,
            store
                .load(id)
                .budget.used.modelRequests,
        )
    }

    @Test
    fun `retry after crash cannot exceed side effect limit`() {
        setup()
        val runtime =
            AgentLoop(store, model(reply(call("write", "write_file", "path" to "new.txt", "content" to "changed"))), afterExecute = {
                throw SimulatedCrash()
            })
        assertThrows(SimulatedCrash::class.java) { start(runtime, BudgetLimits(sideEffects = 1)) }
        val id =
            store.listIds().single()
        AgentLoop(store, model()).drive(id)
        val recovered = AgentLoop(store, model()).recover(id, "retry", "Explicit retry")
        assertEquals(RunStatus.BUDGET_EXHAUSTED, recovered.status)
        assertEquals(1L, recovered.budget.used.sideEffects)
        assertEquals(1, recovered.calls.getValue("write").attempts)
    }

    @Test
    fun `plan and action approvals preserve all counters across new runtime instances`() {
        setup()
        val plan =
            ToolCall(
                "plan",
                "submit_plan",
                """{"goal":"test","steps":[{"description":"write","tools":["write_file"]}],"risks":["write"],"assumptions":["allowed"],"done_when":"done"}""",
            )
        val planning = start(AgentLoop(store, model(reply(plan))), BudgetLimits(), Mode.SAFE, true)
        assertEquals(RunStatus.WAITING_PLAN, planning.status)
        val before =
            store
                .load(planning.runId)
                .budget.used
                .copy()
        val pending =
            AgentLoop(
                store,
                model(reply(call("write", "write_file", "path" to "new.txt", "content" to "changed"))),
            ).planDecision(planning.runId, true, "approved")
        assertEquals(RunStatus.WAITING_ACTION, pending.status)
        assertEquals(before.modelRequests + 1, pending.budget.used.modelRequests)
        assertEquals(before.toolCalls + 1, pending.budget.used.toolCalls)
        assertEquals(before.resultBytes, pending.budget.used.resultBytes)
        val completed = AgentLoop(store, model(Message("assistant", "done"))).actionDecision(planning.runId, true, "approved")
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals(before.modelRequests + 2, completed.budget.used.modelRequests)
        assertEquals(1L, completed.budget.used.sideEffects)
        assertTrue(completed.budget.used.resultBytes > before.resultBytes)
        assertEquals(completed.budget, AgentLoop(store, model()).drive(planning.runId).budget)
    }

    @Test
    fun `policy denial consumes call and result budgets but no side effects`() {
        setup()
        val state =
            start(
                AgentLoop(
                    store,
                    model(
                        reply(call("bash", "Bash", "command" to "rm all", "cwd" to ".")),
                        Message("assistant", "denied"),
                    ),
                ),
                BudgetLimits(),
            )
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(1L, state.budget.used.toolCalls)
        assertEquals(0L, state.budget.used.sideEffects)
        assertTrue(state.budget.used.resultBytes > 0)
        assertEquals(0, state.calls.getValue("bash").attempts)
    }

    @Test
    fun `user rejection is charged as a result and never as a side effect`() {
        setup()
        val pending =
            start(
                AgentLoop(store, model(reply(call("write", "write_file", "path" to "new.txt", "content" to "changed")))),
                BudgetLimits(),
                Mode.SAFE,
            )
        val state = AgentLoop(store, model(Message("assistant", "rejected"))).actionDecision(pending.runId, false, "No writes")
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(1L, state.budget.used.toolCalls)
        assertEquals(0L, state.budget.used.sideEffects)
        assertTrue(state.budget.used.resultBytes > 0)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }

    @Test
    fun `duplicate tool id spends proposal budget but never repeats side effect or result debit`() {
        setup()
        val write = call("write", "write_file", "path" to "new.txt", "content" to "changed")
        val state = start(AgentLoop(store, model(reply(write), reply(write), Message("assistant", "done"))), BudgetLimits())
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(2L, state.budget.used.toolCalls)
        assertEquals(1L, state.budget.used.sideEffects)
        assertEquals(
            state.calls
                .getValue("write")
                .result!!
                .toByteArray(Charsets.UTF_8)
                .size
                .toLong(),
            state.budget.used.resultBytes,
        )
        assertEquals(1, state.calls.getValue("write").attempts)
    }

    @Test
    fun `rejected plan cannot reset exhausted model budget`() {
        setup()
        val plan =
            ToolCall(
                "plan",
                "submit_plan",
                """{"goal":"test","steps":[{"description":"read","tools":["read_file"]}],"risks":["none"],"assumptions":["exists"],"done_when":"done"}""",
            )
        val state = start(AgentLoop(store, model(reply(plan))), BudgetLimits(modelRequests = 1), plan = true)
        val rejected = AgentLoop(store, model()).planDecision(state.runId, false, "Revise")
        assertEquals(RunStatus.BUDGET_EXHAUSTED, rejected.status)
        assertEquals(1L, rejected.budget.used.modelRequests)
        assertEquals(1L, rejected.budget.used.toolCalls)
        assertEquals(state.budget.used.resultBytes, rejected.budget.used.resultBytes)
    }

    @Test
    fun `recover fail retains original side effect debit without new attempt`() {
        setup()
        val runtime =
            AgentLoop(store, model(reply(call("write", "write_file", "path" to "new.txt", "content" to "changed"))), afterExecute = {
                throw SimulatedCrash()
            })
        assertThrows(SimulatedCrash::class.java) { start(runtime, BudgetLimits()) }
        val id =
            store.listIds().single()
        AgentLoop(store, model()).drive(id)
        val state = AgentLoop(store, model(Message("assistant", "failed call acknowledged"))).recover(id, "fail", "Do not retry")
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(1L, state.budget.used.sideEffects)
        assertEquals(1L, state.budget.used.toolCalls)
        assertEquals(1, state.calls.getValue("write").attempts)
        assertTrue(state.budget.used.resultBytes > 0)
    }

    @Test
    fun `explicit retry cannot bypass exhausted tool-call budget`() {
        setup()
        val runtime =
            AgentLoop(store, model(reply(call("write", "write_file", "path" to "new.txt", "content" to "changed"))), afterBudgetDebit = {
                if (it ==
                    "write_file"
                ) {
                    throw SimulatedCrash()
                }
            })
        assertThrows(SimulatedCrash::class.java) { start(runtime, BudgetLimits(toolCalls = 1)) }
        val id =
            store.listIds().single()
        AgentLoop(store, model()).drive(id)
        val state = AgentLoop(store, model()).recover(id, "retry", "Explicit retry")
        assertEquals(RunStatus.BUDGET_EXHAUSTED, state.status)
        assertEquals(1L, state.budget.used.toolCalls)
        assertEquals(1L, state.budget.used.sideEffects)
        assertFalse(Files.exists(workspace.resolve("new.txt")))
    }
}
