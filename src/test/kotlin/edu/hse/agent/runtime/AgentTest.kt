package edu.hse.agent.runtime

import edu.hse.agent.llm.FIXED_ORDER
import edu.hse.agent.llm.ModelClient
import edu.hse.agent.llm.ScriptedModelClient
import edu.hse.agent.models.CallRecord
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.Message
import edu.hse.agent.models.Mode
import edu.hse.agent.models.Phase
import edu.hse.agent.models.PolicyConfig
import edu.hse.agent.models.Rules
import edu.hse.agent.models.RunState
import edu.hse.agent.models.RunStatus
import edu.hse.agent.models.ToolCall
import edu.hse.agent.policy.Policy
import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import edu.hse.agent.storage.JsonRunRepository
import edu.hse.agent.storage.RunRepository
import edu.hse.agent.storage.SqliteRunRepository
import edu.hse.agent.tools.failure
import edu.hse.agent.tools.success
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentTest {
    @TempDir lateinit var directory: Path

    private fun fixture(
        mode: Mode = Mode.AUTO,
        config: PolicyConfig? = null,
    ): Triple<Path, Path, RunRepository> {
        val workspace = Files.createDirectory(directory.resolve("workspace")).toRealPath()
        Files.createDirectory(workspace.resolve("private"))
        Files.writeString(
            workspace.resolve("order.py"),
            "def calculate_total(prices: list[float], discount_percent: float) -> float:\n    return sum(prices) - discount_percent\n",
        )
        Files.writeString(
            workspace.resolve("test_order.py"),
            """
            import unittest
            from order import calculate_total
            class OrderTest(unittest.TestCase):
                def test_discount(self):
                    self.assertEqual(calculate_total([100, 50], 10), 135)
                def test_invalid_discount(self):
                    for value in (-1, 101):
                        with self.assertRaises(ValueError):
                            calculate_total([100], value)
            """.trimIndent() + "\n",
        )
        val policy = directory.resolve("policy.json")
        Files.writeString(
            policy,
            json.encodeToString(
                config ?: PolicyConfig(
                    Rules(listOf("python3 -B -m unittest -v"), listOf("rm -rf .")),
                    Rules(listOf("."), listOf("private")),
                ),
            ),
        )
        return Triple(workspace, policy, SqliteRunRepository(directory.resolve("runs")))
    }

    private fun call(
        id: String,
        name: String,
        vararg fields: Pair<String, String>,
    ) = ToolCall(
        id,
        name,
        json.encodeToString(JsonObject(fields.associate { it.first to JsonPrimitive(it.second) })),
    )

    private fun reply(vararg calls: ToolCall) = Message("assistant", toolCalls = calls.toList())

    private fun model(vararg replies: Message): ModelClient {
        val queue = ArrayDeque(replies.toList())
        return ModelClient { _, _ ->
            check(queue.isNotEmpty()) { "Unexpected model request" }
            queue.removeFirst()
        }
    }

    private fun result(
        state: RunState,
        id: String,
    ) = json.parseToJsonElement(state.calls.getValue(id).result!!).jsonObject

    private fun validPlan(id: String = "plan") =
        ToolCall(
            id,
            "submit_plan",
            """{
        "goal":"Fix order", "steps":[{"description":"Read, fix, test","tools":["read_file","write_file","Bash"]}],
        "risks":["Process has user permissions"], "assumptions":["Python is available"], "done_when":"Tests pass without test edits"
    }""",
        )

    @Test fun `legacy state loads without retaining demo setting`() {
        val (workspace, policy, _) = fixture()
        val store = JsonRunRepository(directory.resolve("legacy-runs"))
        val state = RunState(workspace = workspace.toString(), policyPath = policy.toString(), mode = Mode.SAFE)
        val legacy = json.parseToJsonElement(json.encodeToString(state)).jsonObject
        Files.writeString(
            store.directory.resolve("${state.runId}.json"),
            json.encodeToString(
                JsonObject(legacy + ("demo" to JsonPrimitive(false))),
            ),
        )
        val restored = store.load(state.runId)
        assertEquals(state, restored)
        store.save(restored)
        assertFalse(
            json.parseToJsonElement(Files.readString(store.directory.resolve("${state.runId}.json"))).jsonObject.containsKey("demo"),
        )
    }

    @Test fun `control task uses real Bash and leaves tests unchanged`() {
        val (workspace, policy, store) = fixture()
        val originalTests = Files.readString(workspace.resolve("test_order.py"))
        val state = AgentLoop(store, ScriptedModelClient()).start(workspace, policy, Mode.AUTO, "Fix order; do not change tests")
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(FIXED_ORDER, Files.readString(workspace.resolve("order.py")))
        assertEquals(originalTests, Files.readString(workspace.resolve("test_order.py")))
        val bash = state.calls.values.single { it.call.name == "Bash" }
        assertEquals(0, result(state, bash.call.id).getValue("exit_code").jsonPrimitive.int)
        assertTrue(
            result(state, bash.call.id)
                .getValue("stderr")
                .jsonPrimitive.content
                .contains("OK"),
        )
        assertTrue(state.messages.filter { it.role == "tool" }.all { it.toolCallId != null })
        assertEquals(RunStatus.COMPLETED, AgentLoop(store, model()).drive(state.runId).status)
    }

    @Test fun `safe mode persists and separately approves write and Bash`() {
        val (workspace, policy, store) = fixture()
        val original = Files.readString(workspace.resolve("order.py"))
        val state = AgentLoop(store, ScriptedModelClient()).start(workspace, policy, Mode.SAFE, "Fix order")
        assertEquals(RunStatus.WAITING_ACTION, state.status)
        assertEquals(original, Files.readString(workspace.resolve("order.py")))
        assertEquals(RunStatus.WAITING_ACTION, AgentLoop(store, ScriptedModelClient()).drive(state.runId).status)
        val afterWrite = AgentLoop(store, ScriptedModelClient()).actionDecision(state.runId, true, "Permit write")
        assertEquals(RunStatus.WAITING_ACTION, afterWrite.status)
        assertEquals("Bash", afterWrite.batch[afterWrite.batchIndex].name)
        val completed = AgentLoop(store, ScriptedModelClient()).actionDecision(state.runId, true, "Permit tests")
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals(2, completed.decisions.size)
    }

    @Test fun `rejection becomes tool result and model can choose another step`() {
        val (workspace, policy, store) = fixture()
        val original = Files.readString(workspace.resolve("order.py"))
        val client =
            model(
                reply(call("write", "write_file", "path" to "order.py", "content" to "bad")),
                reply(call("read", "read_file", "path" to "order.py")),
                Message("assistant", "Stopped after refusal"),
            )
        val runtime = AgentLoop(store, client)
        val waiting = runtime.start(workspace, policy, Mode.SAFE, "Fix")
        val state = runtime.actionDecision(waiting.runId, false, "Do not edit")
        assertEquals(RunStatus.COMPLETED, state.status)
        assertTrue(result(state, "write").string("error").contains("Do not edit"))
        assertEquals(original, Files.readString(workspace.resolve("order.py")))
        assertTrue(result(state, "read").getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun `auto mode still requires deletion approval`() {
        val (workspace, policy, store) = fixture()
        val runtime = AgentLoop(store, model(reply(call("delete", "delete_file", "path" to "order.py")), Message("assistant", "Deleted")))
        val waiting = runtime.start(workspace, policy, Mode.AUTO, "Delete")
        assertEquals(RunStatus.WAITING_ACTION, waiting.status)
        assertTrue(Files.exists(workspace.resolve("order.py")))
        assertEquals(RunStatus.COMPLETED, runtime.actionDecision(waiting.runId, true, "Delete this file").status)
        assertFalse(Files.exists(workspace.resolve("order.py")))
    }

    @Test fun `unknown malformed and failed tools do not kill loop`() {
        val (workspace, policy, store) = fixture()
        val state =
            AgentLoop(
                store,
                model(
                    reply(
                        call("unknown", "no_such_tool"),
                        ToolCall("bad-json", "read_file", "{"),
                        call("extra", "read_file", "path" to "order.py", "extra" to "x"),
                        call("missing", "read_file"),
                        call("not-found", "read_file", "path" to "missing.txt"),
                        call("good", "read_file", "path" to "order.py"),
                    ),
                    Message("assistant", "Finished"),
                ),
            ).start(workspace, policy, Mode.AUTO, "Read")
        assertEquals(RunStatus.COMPLETED, state.status)
        for (id in listOf(
            "unknown",
            "bad-json",
            "extra",
            "missing",
            "not-found",
        )) {
            assertFalse(result(state, id).getValue("ok").jsonPrimitive.boolean)
        }
        assertTrue(result(state, "good").getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun `policy blocks deny directories traversal absolute paths and symlinks`() {
        val (workspace, policy, store) = fixture()
        Files.writeString(workspace.resolve("private/secret.txt"), "private")
        Files.createSymbolicLink(workspace.resolve("link.py"), workspace.resolve("order.py"))
        val outside = directory.resolve("outside.txt")
        Files.writeString(outside, "outside")
        Files.createSymbolicLink(workspace.resolve("escape"), outside)
        val state =
            AgentLoop(
                store,
                model(
                    reply(
                        call("private", "read_file", "path" to "private/secret.txt"),
                        call("traversal", "read_file", "path" to "../outside.txt"),
                        call("absolute", "read_file", "path" to outside.toString()),
                        call("inside-link", "read_file", "path" to "link.py"),
                        call("outside-link", "write_file", "path" to "escape", "content" to "changed"),
                        call("cwd", "Bash", "cwd" to "private", "command" to "python3 -B -m unittest -v"),
                        call("deny", "Bash", "cwd" to ".", "command" to "rm -rf ."),
                    ),
                    Message("assistant", "Done"),
                ),
            ).start(workspace, policy, Mode.AUTO, "Check")
        state.calls.keys.forEach { assertFalse(result(state, it).getValue("ok").jsonPrimitive.boolean, it) }
        assertEquals("outside", Files.readString(outside))
    }

    @Test fun `deny wins and configured shell syntax nested commands and path arguments are rejected`() {
        val commands =
            listOf(
                "echo hello | whoami",
                "echo hello > output",
                "echo \$(whoami)",
                "bash -c whoami",
                "python3 -c print",
                "cat private/secret.txt",
                "whoami",
            )
        val (workspace, policy, store) =
            fixture(
                config = PolicyConfig(Rules(commands, listOf("whoami")), Rules(listOf("."), listOf("private"))),
            )
        val state =
            AgentLoop(
                store,
                model(
                    reply(*commands.mapIndexed { i, command -> call("c$i", "Bash", "command" to command, "cwd" to ".") }.toTypedArray()),
                    Message("assistant", "Done"),
                ),
            ).start(workspace, policy, Mode.AUTO, "Check")
        state.calls.keys.forEach { assertFalse(result(state, it).getValue("ok").jsonPrimitive.boolean, it) }
    }

    @Test fun `invalid policy location and escaping policy directories are rejected`() {
        val (workspace, policy, _) = fixture()
        val inside = workspace.resolve("policy.json")
        Files.copy(policy, inside)
        assertThrows(IllegalArgumentException::class.java) { Policy(workspace, inside) }
        Files.writeString(policy, json.encodeToString(PolicyConfig(Rules(emptyList(), emptyList()), Rules(listOf(".."), emptyList()))))
        assertThrows(IllegalArgumentException::class.java) { Policy(workspace, policy) }
    }

    @Test fun `policy is rechecked after confirmation`() {
        val (workspace, policy, store) = fixture()
        val client = model(reply(call("write", "write_file", "path" to "order.py", "content" to "changed")), Message("assistant", "Done"))
        val runtime = AgentLoop(store, client)
        val waiting = runtime.start(workspace, policy, Mode.SAFE, "Write")
        Files.writeString(policy, json.encodeToString(PolicyConfig(Rules(emptyList(), emptyList()), Rules(listOf("."), listOf(".")))))
        val state = runtime.actionDecision(waiting.runId, true, "Approve")
        assertFalse(result(state, "write").getValue("ok").jsonPrimitive.boolean)
        assertFalse(Files.readString(workspace.resolve("order.py")) == "changed")
    }

    @Test fun `Bash timeout and shared stdout stderr cap are enforced`() {
        val (workspace, policy, store) =
            fixture(
                config =
                    PolicyConfig(
                        Rules(listOf("sleep 2", "seq 1 10000", "false"), emptyList()),
                        Rules(listOf("."), listOf("private")),
                        bashTimeoutMillis = 100,
                        maxOutputBytes = 100,
                    ),
            )
        val state =
            AgentLoop(
                store,
                model(
                    reply(
                        call("timeout", "Bash", "command" to "sleep 2", "cwd" to "."),
                        call("output", "Bash", "command" to "seq 1 10000", "cwd" to "."),
                        call("exit", "Bash", "command" to "false", "cwd" to "."),
                    ),
                    Message("assistant", "Done"),
                ),
            ).start(workspace, policy, Mode.AUTO, "Test")
        assertTrue(result(state, "timeout").getValue("timed_out").jsonPrimitive.boolean)
        assertTrue(result(state, "output").getValue("truncated").jsonPrimitive.boolean)
        assertTrue(
            result(
                state,
                "output",
            ).string("stdout").toByteArray().size + result(state, "output").string("stderr").toByteArray().size <= 100,
        )
        assertEquals(1, result(state, "exit").getValue("exit_code").jsonPrimitive.int)
    }

    @Test fun `duplicate tool id does not repeat write and mismatch is a protocol error`() {
        val (workspace, policy, store) = fixture()
        val write = call("write", "write_file", "path" to "order.py", "content" to "first")
        var writes = 0
        val state =
            AgentLoop(
                store,
                model(
                    reply(
                        write,
                        write,
                        call("write", "write_file", "path" to "order.py", "content" to "second"),
                    ),
                    Message("assistant", "Done"),
                ),
                afterExecute = { if (it.name == "write_file") writes++ },
            ).start(workspace, policy, Mode.AUTO, "Write")
        assertEquals(1, writes)
        assertEquals("first", Files.readString(workspace.resolve("order.py")))
        assertTrue(
            state.messages
                .last { it.role == "tool" }
                .content!!
                .contains("Protocol error"),
        )
    }

    @Test fun `crash leaves uncertain write and resume never silently retries`() {
        val (workspace, policy, store) = fixture()
        var id = ""
        assertThrows(SimulatedCrash::class.java) {
            AgentLoop(
                store,
                model(reply(call("write", "write_file", "path" to "order.py", "content" to "written"))),
                afterExecute = { throw SimulatedCrash() },
                onCreated = { id = it.runId },
            ).start(workspace, policy, Mode.AUTO, "Write")
        }
        assertEquals("written", Files.readString(workspace.resolve("order.py")))
        assertEquals(
            CallStatus.STARTED,
            store
                .load(id)
                .calls
                .getValue("write")
                .status,
        )
        Files.writeString(workspace.resolve("order.py"), "external edit")
        val runtime = AgentLoop(store, model(Message("assistant", "Done")))
        assertEquals(RunStatus.NEEDS_RECOVERY, runtime.drive(id).status)
        assertEquals("external edit", Files.readString(workspace.resolve("order.py")))
        val recovered = runtime.recover(id, "retry", "Inspected file; explicitly repeat replacement")
        assertEquals(RunStatus.COMPLETED, recovered.status)
        assertEquals(2, recovered.calls.getValue("write").attempts)
        assertEquals("written", Files.readString(workspace.resolve("order.py")))
    }

    @Test fun `uncertain Bash cannot be retried but can be failed`() {
        val (workspace, policy, store) = fixture(config = PolicyConfig(Rules(listOf("true"), emptyList()), Rules(listOf("."), emptyList())))
        var id = ""
        assertThrows(SimulatedCrash::class.java) {
            AgentLoop(
                store,
                model(reply(call("bash", "Bash", "command" to "true", "cwd" to "."))),
                afterExecute = { throw SimulatedCrash() },
                onCreated = { id = it.runId },
            ).start(workspace, policy, Mode.AUTO, "Run")
        }
        val runtime = AgentLoop(store, model(Message("assistant", "Acknowledged failure")))
        runtime.drive(id)
        assertThrows(IllegalArgumentException::class.java) { runtime.recover(id, "retry", "Repeat") }
        val state = runtime.recover(id, "fail", "Consider unsuccessful")
        assertEquals(RunStatus.COMPLETED, state.status)
        assertFalse(result(state, "bash").getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun `uncertain call can terminate run with error`() {
        val (workspace, policy, store) = fixture()
        var id = ""
        assertThrows(SimulatedCrash::class.java) {
            AgentLoop(
                store,
                model(reply(call("read", "read_file", "path" to "order.py"))),
                afterExecute = { throw SimulatedCrash() },
                onCreated = { id = it.runId },
            ).start(workspace, policy, Mode.AUTO, "Read")
        }
        val runtime = AgentLoop(store, model())
        runtime.drive(id)
        assertEquals(RunStatus.FAILED, runtime.recover(id, "abort", "Stop").status)
        assertEquals(RunStatus.FAILED, runtime.drive(id).status)
    }

    @Test fun `Plan Mode blocks side effects even in same batch as submitted plan`() {
        val (workspace, policy, store) = fixture()
        val before = Files.readString(workspace.resolve("order.py"))
        val state =
            AgentLoop(
                store,
                model(
                    reply(
                        call("read", "read_file", "path" to "order.py"),
                        validPlan(),
                        call("write", "write_file", "path" to "order.py", "content" to "bad"),
                        call("bash", "Bash", "command" to "python3 -B -m unittest -v", "cwd" to "."),
                        call("delete", "delete_file", "path" to "test_order.py"),
                    ),
                ),
            ).start(workspace, policy, Mode.AUTO, "Plan fix", plan = true)
        assertEquals(RunStatus.WAITING_PLAN, state.status)
        assertEquals(before, Files.readString(workspace.resolve("order.py")))
        assertTrue(Files.exists(workspace.resolve("test_order.py")))
        for (id in listOf("write", "bash", "delete")) {
            assertFalse(result(state, id).getValue("ok").jsonPrimitive.boolean)
            assertEquals(0, state.calls.getValue(id).attempts)
        }
        assertNotNull(store.load(state.runId).plan)
    }

    @Test fun `plan approval does not preapprove safe actions`() {
        val (workspace, policy, store) = fixture()
        val runtime = AgentLoop(store, ScriptedModelClient())
        val planning = runtime.start(workspace, policy, Mode.SAFE, "Fix order", plan = true)
        assertEquals(RunStatus.WAITING_PLAN, planning.status)
        val afterPlan = AgentLoop(store, ScriptedModelClient()).planDecision(planning.runId, true, "Approve plan")
        assertEquals(Phase.EXECUTE, afterPlan.phase)
        assertEquals(RunStatus.WAITING_ACTION, afterPlan.status)
        val afterWrite = AgentLoop(store, ScriptedModelClient()).actionDecision(planning.runId, true, "Approve write")
        assertEquals(RunStatus.WAITING_ACTION, afterWrite.status)
        assertEquals(
            RunStatus.COMPLETED,
            AgentLoop(store, ScriptedModelClient()).actionDecision(planning.runId, true, "Approve Bash").status,
        )
    }

    @Test fun `plan rejection can produce a revised plan`() {
        val (workspace, policy, store) = fixture()
        val runtime = AgentLoop(store, model(reply(validPlan()), reply(validPlan("revised"))))
        val state = runtime.start(workspace, policy, Mode.AUTO, "Plan", plan = true)
        val revised = runtime.planDecision(state.runId, false, "Add validation")
        assertEquals(RunStatus.WAITING_PLAN, revised.status)
        assertTrue(revised.messages.any { it.role == "user" && it.content!!.contains("Add validation") })
        assertEquals("reject", revised.decisions.single().choice)
    }

    @Test fun `state directory inside workspace is forbidden`() {
        val (workspace, policy, _) = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            AgentLoop(SqliteRunRepository(workspace.resolve("runs")), model()).start(workspace, policy, Mode.AUTO, "Task")
        }
    }

    @Test fun `Plan Mode text without structured plan is not successful completion`() {
        val (workspace, policy, store) = fixture()
        val state =
            AgentLoop(store, model(Message("assistant", "Here is a text-only plan")))
                .start(workspace, policy, Mode.AUTO, "Plan", plan = true)
        assertEquals(RunStatus.FAILED, state.status)
        assertNull(state.plan)
        assertTrue(state.error!!.contains("structured submit_plan"))
    }

    @Test fun `model transport failure can be resumed without repeating completed tool`() {
        val (workspace, policy, store) = fixture()
        var requests = 0
        val state =
            AgentLoop(
                store,
                ModelClient { _, _ ->
                    if (requests++ ==
                        0
                    ) {
                        reply(call("write", "write_file", "path" to "order.py", "content" to "done"))
                    } else {
                        error("Transport offline")
                    }
                },
            ).start(workspace, policy, Mode.AUTO, "Write")
        assertEquals("Transport offline", state.error)
        Files.writeString(workspace.resolve("order.py"), "external")
        val completed = AgentLoop(store, model(Message("assistant", "Finished"))).drive(state.runId)
        assertEquals(RunStatus.COMPLETED, completed.status)
        assertEquals("external", Files.readString(workspace.resolve("order.py")))
        assertNull(completed.error)
    }

    @Test fun `DONE persisted before tool message is recovered without executing again`() {
        val (workspace, policy, store) = fixture()
        val write = call("write", "write_file", "path" to "order.py", "content" to "original result")
        val state = RunState(workspace = workspace.toString(), policyPath = policy.toString(), mode = Mode.AUTO)
        state.batch = listOf(write)
        state.calls[write.id] = CallRecord(write, CallStatus.DONE, success("path" to JsonPrimitive("order.py")), attempts = 1)
        state.messages += reply(write)
        store.save(state)
        Files.writeString(workspace.resolve("order.py"), "external")
        val completed = AgentLoop(store, model(Message("assistant", "Done"))).drive(state.runId)
        assertEquals("external", Files.readString(workspace.resolve("order.py")))
        assertEquals(1, completed.messages.count { it.role == "tool" && it.toolCallId == "write" })
    }
}
