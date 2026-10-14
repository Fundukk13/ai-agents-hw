package edu.hse.agent.tasks

import edu.hse.agent.models.AgentTask
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.Message
import edu.hse.agent.models.RunState
import edu.hse.agent.models.TaskEvidence
import edu.hse.agent.models.TaskStatus
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import edu.hse.agent.texts.Prompts
import edu.hse.agent.tools.ToolRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TaskManager {
    fun fromPlan(plan: JsonObject): List<AgentTask> {
        val tasks =
            plan.getValue("steps").jsonArray.map { value ->
                val step = value.jsonObject
                AgentTask(
                    step.string("id"),
                    step.string("description"),
                    step.getValue("depends_on").jsonArray.map { it.jsonPrimitive.content },
                    step.getValue("tools").jsonArray.map { it.jsonPrimitive.content },
                    step.string("verification_tool"),
                )
            }
        require(tasks.isNotEmpty()) { "Task plan must not be empty" }
        require(tasks.map { it.id }.distinct().size == tasks.size) { "Duplicate task id" }
        val names = ToolRegistry.definitions.map { it.name }.toSet()
        val ids = tasks.map { it.id }.toSet()
        tasks.forEach { task ->
            require(Regex("[A-Za-z0-9_-]{1,64}").matches(task.id) && task.description.isNotBlank()) { "Invalid task id or description" }
            require(task.dependencies.distinct().size == task.dependencies.size) { "Duplicate dependency" }
            require(task.dependencies.all { it in ids && it != task.id }) { "Unknown or self dependency" }
            require(task.tools.isNotEmpty() && task.tools.all { it in names }) { "Unknown execution tool in task" }
            require(task.verificationTool in task.tools) { "Verification tool must be allowed by the task" }
        }
        val completed = mutableSetOf<String>()
        while (completed.size < tasks.size) {
            val ready = tasks.filter { it.id !in completed && completed.containsAll(it.dependencies) }
            require(ready.isNotEmpty()) { "Task dependencies contain a cycle" }
            completed += ready.map { it.id }
        }
        return tasks
    }

    fun activate(state: RunState) {
        if (state.currentTaskId != null) return
        val task =
            state.tasks.firstOrNull { candidate ->
                candidate.status == TaskStatus.PENDING &&
                    candidate.dependencies.all { dependency ->
                        state.tasks.single { it.id == dependency }.status == TaskStatus.COMPLETED
                    }
            }
        if (task == null) {
            require(state.tasks.all { it.status == TaskStatus.COMPLETED }) { "No task with successful dependencies is available" }
            if (!state.taskFinalRequested) {
                state.messages += Message("user", Prompts.tasksCompleted)
                state.taskFinalRequested = true
            }
            return
        }
        task.status = TaskStatus.RUNNING
        state.currentTaskId = task.id
        state.messages +=
            Message(
                "user",
                Prompts.currentTask(json.encodeToString(task)),
            )
    }

    fun check(
        state: RunState,
        call: ToolCall,
    ) {
        val task = state.tasks.singleOrNull { it.id == state.batchTaskId } ?: error("No current task")
        require(task.status == TaskStatus.RUNNING) { "Current task is not running" }
        require(call.name == "complete_task" || call.name in task.tools) { "Tool is not allowed by current task" }
    }

    fun complete(
        state: RunState,
        arguments: JsonObject,
    ) {
        val task = state.tasks.singleOrNull { it.id == arguments.string("task_id") } ?: error("Unknown task id")
        require(state.batch.size == 1) { "complete_task must be the only call in its batch" }
        require(task.id == state.currentTaskId && task.id == state.batchTaskId && task.status == TaskStatus.RUNNING) {
            "Only current running task can be completed"
        }
        require(
            task.dependencies.all { id ->
                state.tasks.single { it.id == id }.status == TaskStatus.COMPLETED
            },
        ) { "Dependencies are not completed" }
        val record = state.calls[arguments.string("call_id")] ?: error("Evidence call does not exist")
        require(record.taskId == task.id && record.status == CallStatus.DONE && record.attempts > 0) {
            "Evidence must be an executed call of this task"
        }
        require(record.call.name == task.verificationTool) { "Wrong verification tool" }
        val result = requireNotNull(record.result)
        val value = json.parseToJsonElement(result).jsonObject
        require(value["ok"]?.jsonPrimitive?.booleanOrNull == true && value["truncated"]?.jsonPrimitive?.booleanOrNull != true) {
            "Evidence is unsuccessful or incomplete"
        }
        if (record.call.name == "Bash") {
            require(value["exit_code"]?.jsonPrimitive?.intOrNull == 0 && value["timed_out"]?.jsonPrimitive?.booleanOrNull == false) {
                "Bash verification did not pass"
            }
        }
        task.result = TaskEvidence(record.call.id, record.call.name, result)
        task.status = TaskStatus.COMPLETED
        state.currentTaskId = null
    }

    fun failCurrent(state: RunState) {
        state.tasks.filter { it.id == state.currentTaskId }.forEach { it.status = TaskStatus.FAILED }
        state.tasks.filter { it.status == TaskStatus.PENDING }.forEach { it.status = TaskStatus.BLOCKED }
    }
}
