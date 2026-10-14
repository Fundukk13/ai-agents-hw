package edu.hse.agent.tasks

import edu.hse.agent.models.AgentTask
import edu.hse.agent.models.CallRecord
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.Mode
import edu.hse.agent.models.RunState
import edu.hse.agent.models.TaskStatus
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TaskManagerTest {
    private val manager = TaskManager()

    @ParameterizedTest
    @ValueSource(
        strings = [
            "[{\"id\":\"a\",\"depends_on\":[\"missing\"]}]",
            "[{\"id\":\"a\",\"depends_on\":[\"a\"]}]",
            "[{\"id\":\"a\",\"depends_on\":[]},{\"id\":\"a\",\"depends_on\":[]}]",
            "[{\"id\":\"a\",\"depends_on\":[\"b\"]},{\"id\":\"b\",\"depends_on\":[\"a\"]}]",
            "[{\"id\":\"bad id\",\"depends_on\":[]}]",
            "[{\"id\":\"a\",\"depends_on\":[\"b\",\"b\"]},{\"id\":\"b\",\"depends_on\":[]}]",
        ],
    )
    fun `invalid dependency graphs are rejected`(steps: String) {
        val array = json.parseToJsonElement(steps) as kotlinx.serialization.json.JsonArray
        val enriched =
            kotlinx.serialization.json.JsonArray(
                array.map { step ->
                    JsonObject(
                        step.jsonObject +
                            mapOf(
                                "description" to JsonPrimitive("Read"),
                                "tools" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("read_file"))),
                                "verification_tool" to JsonPrimitive("read_file"),
                            ),
                    )
                },
            )
        assertThrows(IllegalArgumentException::class.java) { manager.fromPlan(JsonObject(mapOf("steps" to enriched))) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "{\"ok\":false,\"error\":\"failed\"}",
            "{\"ok\":true,\"truncated\":true}",
            "{\"truncated\":true}",
            "{\"ok\":true,\"exit_code\":1,\"timed_out\":false}",
            "{\"ok\":true,\"exit_code\":0,\"timed_out\":true}",
        ],
    )
    fun `failed truncated or timed-out Bash cannot prove completion`(result: String) {
        val task = AgentTask("test", "Test", emptyList(), listOf("Bash"), "Bash", TaskStatus.RUNNING)
        val call = ToolCall("bash", "Bash", "{}")
        val state = RunState(workspace = "/workspace", policyPath = "/policy", mode = Mode.AUTO, tasksEnabled = true)
        state.tasks += task
        state.currentTaskId = task.id
        state.batchTaskId = task.id
        state.batch = listOf(ToolCall("done", "complete_task", "{}"))
        state.calls[call.id] = CallRecord(call, CallStatus.DONE, result, attempts = 1, taskId = task.id)
        assertThrows(IllegalArgumentException::class.java) {
            manager.complete(
                state,
                JsonObject(
                    mapOf(
                        "task_id" to JsonPrimitive(task.id),
                        "call_id" to JsonPrimitive(call.id),
                    ),
                ),
            )
        }
        assertEquals(TaskStatus.RUNNING, task.status)
        assertEquals(null, task.result)
    }

    @Test
    fun `activation never chooses task before successful dependencies`() {
        val state = RunState(workspace = "/workspace", policyPath = "/policy", mode = Mode.AUTO)
        state.tasks += AgentTask("second", "Second", listOf("first"), listOf("read_file"), "read_file")
        state.tasks += AgentTask("first", "First", emptyList(), listOf("read_file"), "read_file")
        manager.activate(state)
        assertEquals("first", state.currentTaskId)
        assertEquals(TaskStatus.PENDING, state.tasks.first().status)
    }
}
