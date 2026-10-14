package edu.hse.agent.runtime

import edu.hse.agent.cli.TaskRenderer
import edu.hse.agent.llm.FIXED_ORDER
import edu.hse.agent.llm.ModelClient
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.Message
import edu.hse.agent.models.Mode
import edu.hse.agent.models.Phase
import edu.hse.agent.models.PolicyConfig
import edu.hse.agent.models.Rules
import edu.hse.agent.models.RunState
import edu.hse.agent.models.RunStatus
import edu.hse.agent.models.TaskStatus
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import edu.hse.agent.storage.RunRepository
import edu.hse.agent.storage.SqliteRunRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TaskGraphTest {
    @TempDir lateinit var directory: Path
    private lateinit var workspace: Path
    private lateinit var policy: Path
    private lateinit var store: RunRepository

    private fun setup() {
        workspace = Files.createDirectory(directory.resolve("workspace"))
        Files.copy(Path.of("workspace/order.py"), workspace.resolve("order.py"))
        Files.copy(Path.of("workspace/test_order.py"), workspace.resolve("test_order.py"))
        policy = directory.resolve("policy.json")
        Files.writeString(
            policy,
            json.encodeToString(PolicyConfig(Rules(listOf("python3 -B -m unittest -v"), emptyList()), Rules(listOf("."), emptyList()))),
        )
        store = SqliteRunRepository(directory.resolve("runs"))
    }

    private fun call(
        id: String,
        name: String,
        vararg fields: Pair<String, String>,
    ) = ToolCall(id, name, json.encodeToString(JsonObject(fields.associate { it.first to JsonPrimitive(it.second) })))

    private fun reply(vararg calls: ToolCall) = Message("assistant", toolCalls = calls.toList())

    private fun model(vararg messages: Message): ModelClient {
        val queue = ArrayDeque(messages.toList())
        return ModelClient { _, _ ->
            check(queue.isNotEmpty()) { "Unexpected model request" }
            queue.removeFirst()
        }
    }

    private fun plan() =
        reply(
            ToolCall(
                "plan",
                "submit_plan",
                """{
        "goal":"Fix order", "steps":[
          {"id":"test","description":"Run tests","depends_on":["modify"],"tools":["Bash"],"verification_tool":"Bash"},
          {"id":"inspect","description":"Read code","depends_on":[],"tools":["read_file"],"verification_tool":"read_file"},
          {"id":"modify","description":"Fix code","depends_on":["inspect"],"tools":["write_file"],"verification_tool":"write_file"}
        ], "risks":["Bash"], "assumptions":["Python available"], "done_when":"Tests pass without test edits"
    }""",
            ),
        )

    private fun read() = reply(call("read", "read_file", "path" to "order.py"))

    private fun write() = reply(call("write", "write_file", "path" to "order.py", "content" to FIXED_ORDER))

    private fun bash() = reply(call("bash", "Bash", "command" to "python3 -B -m unittest -v", "cwd" to "."))

    private fun done(
        task: String,
        evidence: String,
    ) = reply(call("done-$task", "complete_task", "task_id" to task, "call_id" to evidence))

    private fun start(mode: Mode = Mode.AUTO): RunState =
        AgentLoop(store, model(plan())).start(workspace, policy, mode, "Fix order", tasksEnabled = true)

    @Test
    fun `three dependent tasks run in dependency order with real evidence and unchanged tests`() {
        setup()
        val before = Files.readString(workspace.resolve("order.py"))
        val tests = Files.readString(workspace.resolve("test_order.py"))
        val planning = start()
        assertEquals(RunStatus.WAITING_PLAN, planning.status)
        assertTrue(planning.tasks.all { it.status == TaskStatus.PENDING })
        assertEquals(before, Files.readString(workspace.resolve("order.py")))
        val completed =
            AgentLoop(
                store,
                model(
                    read(),
                    done("inspect", "read"),
                    write(),
                    done("modify", "write"),
                    bash(),
                    done("test", "bash"),
                    Message("assistant", "done"),
                ),
            ).planDecision(planning.runId, true, "Approved")
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertTrue(completed.tasks.all { it.status == TaskStatus.COMPLETED && it.result != null })
        assertEquals(
            listOf("inspect", "modify", "test"),
            completed.calls.values
                .filter { it.attempts > 0 }
                .map { it.taskId },
        )
        assertTrue(
            completed.calls.values
                .filter { it.phase == Phase.EXECUTE }
                .all { it.taskId != null },
        )
        assertEquals(listOf("plan"), completed.planningCallIds)
        assertEquals(null, completed.calls.getValue("plan").taskId)
        assertEquals(tests, Files.readString(workspace.resolve("test_order.py")))
        assertTrue(
            completed.tasks
                .single { it.id == "test" }
                .result!!
                .result
                .contains("OK"),
        )
        assertEquals(3, TaskRenderer().render(completed.tasks).lines().count { it.startsWith("[x]") })
        assertEquals(completed.tasks, AgentLoop(store, model()).drive(completed.runId).tasks)
    }

    @Test
    fun `resume after transport failure skips completed task and retains evidence and budget`() {
        setup()
        val planning = start()
        val replies = ArrayDeque(listOf(read(), done("inspect", "read")))
        val failing = ModelClient { _, _ -> if (replies.isEmpty()) error("Network error") else replies.removeFirst() }
        val stopped = AgentLoop(store, failing).planDecision(planning.runId, true, "Approved")
        assertEquals(RunStatus.RUNNING, stopped.status)
        assertEquals(TaskStatus.COMPLETED, stopped.tasks.single { it.id == "inspect" }.status)
        val evidence = stopped.tasks.single { it.id == "inspect" }.result
        val requests = stopped.budget.used.modelRequests
        val completed =
            AgentLoop(
                store,
                model(write(), done("modify", "write"), bash(), done("test", "bash"), Message("assistant", "done")),
            ).drive(stopped.runId)
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals(evidence, completed.tasks.single { it.id == "inspect" }.result)
        assertEquals(1, completed.calls.getValue("read").attempts)
        assertTrue(completed.budget.used.modelRequests > requests)
    }

    @Test
    fun `safe rejection fails current task and never starts dependent task`() {
        setup()
        val before = Files.readString(workspace.resolve("order.py"))
        val planning = start(Mode.SAFE)
        val pending = AgentLoop(store, model(read(), done("inspect", "read"), write())).planDecision(planning.runId, true, "Approved")
        assertEquals(RunStatus.WAITING_ACTION, pending.status)
        assertEquals("modify", pending.currentTaskId)
        val failed = AgentLoop(store, model(Message("assistant", "Cannot continue"))).actionDecision(pending.runId, false, "No writes")
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals(TaskStatus.FAILED, failed.tasks.single { it.id == "modify" }.status)
        assertEquals(TaskStatus.BLOCKED, failed.tasks.single { it.id == "test" }.status)
        assertEquals(before, Files.readString(workspace.resolve("order.py")))
        assertEquals(0L, failed.budget.used.sideEffects)
    }

    @Test
    fun `text claim alone never completes a task`() {
        setup()
        val planning = start()
        val failed = AgentLoop(store, model(Message("assistant", "Everything is complete"))).planDecision(planning.runId, true, "Approved")
        assertEquals(RunStatus.FAILED, failed.status)
        assertTrue(failed.tasks.none { it.status == TaskStatus.COMPLETED })
        assertTrue(failed.tasks.all { it.result == null })
    }

    @Test
    fun `cross-task evidence and batched completion are rejected without skipping dependencies`() {
        setup()
        val planning = start()
        val completed =
            AgentLoop(
                store,
                model(
                    read(),
                    reply(
                        call("bad-batched", "complete_task", "task_id" to "inspect", "call_id" to "read"),
                        call(
                            "read2",
                            "read_file",
                            "path" to "order.py",
                        ),
                    ),
                    done("inspect", "read"),
                    write(),
                    reply(call("wrong-proof", "complete_task", "task_id" to "modify", "call_id" to "read")),
                    done("modify", "write"),
                    bash(),
                    done("test", "bash"),
                    Message("assistant", "done"),
                ),
            ).planDecision(planning.runId, true, "Approved")
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertTrue(
            completed.calls
                .getValue("bad-batched")
                .result!!
                .contains("only call"),
        )
        assertTrue(
            completed.calls
                .getValue("wrong-proof")
                .result!!
                .contains("this task"),
        )
        assertEquals(
            "write",
            completed.tasks
                .single { it.id == "modify" }
                .result!!
                .callId,
        )
    }

    @Test
    fun `crash in second task preserves first task and requires explicit recovery`() {
        setup()
        val planning = start()
        val crashing =
            AgentLoop(store, model(read(), done("inspect", "read"), write()), afterExecute = {
                if (it.name ==
                    "write_file"
                ) {
                    throw SimulatedCrash()
                }
            })
        assertThrows(SimulatedCrash::class.java) { crashing.planDecision(planning.runId, true, "Approved") }
        val stopped = AgentLoop(store, model()).drive(planning.runId)
        assertEquals(RunStatus.NEEDS_RECOVERY, stopped.status)
        assertEquals(TaskStatus.COMPLETED, stopped.tasks.single { it.id == "inspect" }.status)
        assertEquals(TaskStatus.PENDING, stopped.tasks.single { it.id == "test" }.status)
        assertEquals("modify", stopped.calls.getValue("write").taskId)
        val completed =
            AgentLoop(
                store,
                model(done("modify", "write"), bash(), done("test", "bash"), Message("assistant", "done")),
            ).recover(planning.runId, "retry", "Inspected write")
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals(1, completed.calls.getValue("read").attempts)
        assertEquals(2, completed.calls.getValue("write").attempts)
        assertNotNull(completed.tasks.single { it.id == "inspect" }.result)
    }

    @Test
    fun `tool outside current task cannot execute`() {
        setup()
        val planning = start()
        val stopped = AgentLoop(store, model(write(), Message("assistant", "stop"))).planDecision(planning.runId, true, "Approved")
        assertEquals(RunStatus.FAILED, stopped.status)
        assertEquals(0, stopped.calls.getValue("write").attempts)
        assertEquals("inspect", stopped.calls.getValue("write").taskId)
        assertEquals(0L, stopped.budget.used.sideEffects)
    }
}
