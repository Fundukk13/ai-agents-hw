package edu.hse.agent.runtime

import edu.hse.agent.budget.BudgetExceeded
import edu.hse.agent.budget.BudgetManager
import edu.hse.agent.budget.ResultLimiter
import edu.hse.agent.llm.ModelClient
import edu.hse.agent.models.BudgetLimits
import edu.hse.agent.models.BudgetState
import edu.hse.agent.models.CallRecord
import edu.hse.agent.models.CallStatus
import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.Decision
import edu.hse.agent.models.Message
import edu.hse.agent.models.Mode
import edu.hse.agent.models.Phase
import edu.hse.agent.models.RunState
import edu.hse.agent.models.RunStatus
import edu.hse.agent.models.TaskStatus
import edu.hse.agent.models.ToolCall
import edu.hse.agent.policy.Policy
import edu.hse.agent.serialization.json
import edu.hse.agent.storage.RunRepository
import edu.hse.agent.tasks.TaskManager
import edu.hse.agent.tools.Schemas
import edu.hse.agent.tools.ToolExecutor
import edu.hse.agent.tools.failure
import edu.hse.agent.tools.success
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

class AgentLoop(
    private val store: RunRepository,
    private val model: ModelClient,
    private val toolExecutor: ToolExecutor = ToolExecutor(),
    private val afterExecute: (ToolCall) -> Unit = {},
    private val onCreated: (RunState) -> Unit = {},
    private val onProgress: (RunState, String) -> Unit = { _, _ -> },
    private val afterBudgetDebit: (String) -> Unit = {},
) {
    private val taskManager = TaskManager()
    private val budgets = BudgetManager()
    private val resultLimiter = ResultLimiter()

    fun start(
        workspace: Path,
        policyPath: Path,
        mode: Mode,
        task: String,
        plan: Boolean = false,
        budgetLimits: BudgetLimits = BudgetLimits(),
        tasksEnabled: Boolean = false,
    ): RunState {
        val policy = Policy(workspace, policyPath)
        require(!store.directory.startsWith(policy.root)) { "State directory must be outside workspace" }
        val state =
            RunState(
                workspace = policy.root.toString(),
                policyPath = policy.file.toString(),
                mode = mode,
                budget = BudgetState(budgetLimits),
                tasksEnabled = tasksEnabled,
                phase = if (plan || tasksEnabled) Phase.PLAN else Phase.EXECUTE,
            )
        val phaseInstruction =
            if (plan || tasksEnabled) {
                "Start in Plan Mode: only read tools are allowed. " +
                    "Use submit_plan with goal, steps, tools, risks, assumptions and done_when; await approval."
            } else {
                "Start execution now."
            }
        state.messages +=
            Message(
                "system",
                """You are a console coding agent. Use tools to inspect and change the project, then verify with Bash.
            |All file paths and cwd are relative to workspace. Never change tests unless the user's task explicitly requires it.
            |Tool calls are checked by runtime; an error is data, choose a different step or explain why you cannot proceed.
            |Finish with a factual text answer. Do not claim tests passed unless a tool result proves it.
            |$phaseInstruction
            |Allowed Bash commands: ${policy.config.commands.allow}. Denied directories: ${policy.config.directories.deny}.
                """.trimMargin(),
            )
        if (tasksEnabled) {
            state.messages +=
                Message(
                    "system",
                    "Task Mode: submit_plan steps must include id, depends_on, tools and verification_tool. Execution is scheduled by runtime. Every task requires complete_task(task_id, call_id) with successful verification evidence. Do not complete tasks by text claims.",
                )
        }
        state.messages += Message("user", task)
        store.locked(state.runId) { store.save(state) }
        onCreated(state)
        return drive(state.runId)
    }

    fun drive(id: String): RunState = store.locked(id) { advance(store.load(id)) }

    fun actionDecision(
        id: String,
        approve: Boolean,
        reason: String,
    ): RunState =
        store.locked(id) {
            val state = store.load(id)
            require(state.status == RunStatus.WAITING_ACTION) { "Run is not waiting for action approval" }
            val record = state.calls.getValue(state.batch[state.batchIndex].id)
            require(record.status == CallStatus.WAITING_CONFIRMATION)
            state.decisions += Decision("action", record.call.id, if (approve) "approve" else "reject", reason)
            if (approve) {
                record.approved = true
                record.status = CallStatus.PENDING
            } else {
                try {
                    saveResult(state, record, failure("User rejected action: $reason"))
                } catch (e: BudgetExceeded) {
                    return@locked exhausted(state, e)
                }
            }
            state.status = RunStatus.RUNNING
            store.save(state)
            advance(state)
        }

    fun planDecision(
        id: String,
        approve: Boolean,
        reason: String,
    ): RunState =
        store.locked(id) {
            val state = store.load(id)
            require(state.status == RunStatus.WAITING_PLAN) { "Run is not waiting for plan approval" }
            state.decisions += Decision("plan", id, if (approve) "approve" else "reject", reason)
            if (approve) {
                state.phase = Phase.EXECUTE
                state.messages += Message("user", "Plan approved. Execute it now.")
            } else {
                state.plan = null
                state.tasks.clear()
                state.currentTaskId = null
                state.taskFinalRequested = false
                state.messages += Message("user", "Plan rejected: $reason. Revise the plan or explain why you cannot proceed.")
            }
            state.status = RunStatus.RUNNING
            store.save(state)
            advance(state)
        }

    fun recover(
        id: String,
        choice: String,
        reason: String,
    ): RunState =
        store.locked(id) {
            val state = store.load(id)
            require(
                state.status == RunStatus.NEEDS_RECOVERY || state.calls.values.any { it.status == CallStatus.STARTED },
            ) { "No uncertain call" }
            val record = state.calls.getValue(state.batch[state.batchIndex].id)
            require(record.status == CallStatus.STARTED) { "Current call is not uncertain" }
            when (choice) {
                "retry" -> {
                    require(Schemas.definition(record.call.name).repeatable) { "Tool is not safely repeatable; choose fail or abort" }
                    record.status = CallStatus.PENDING
                    record.retryPending = true

                    record.approved = true
                    state.status = RunStatus.RUNNING
                }

                "fail" -> {
                    try {
                        saveResult(state, record, failure("Uncertain call considered unsuccessful: $reason"))
                    } catch (e: BudgetExceeded) {
                        return@locked exhausted(state, e)
                    }
                    state.status =
                        RunStatus.RUNNING
                }

                "abort" -> {
                    state.status = RunStatus.FAILED
                    state.error = "User aborted uncertain call: $reason"
                }

                else -> {
                    error("Recovery choice must be retry, fail or abort")
                }
            }
            state.decisions += Decision("recovery", record.call.id, choice, reason)
            store.save(state)
            advance(state)
        }

    private fun sameCall(
        first: ToolCall,
        second: ToolCall,
    ): Boolean =
        first.name == second.name &&
            runCatching { json.parseToJsonElement(first.arguments) == json.parseToJsonElement(second.arguments) }.getOrElse {
                first.arguments ==
                    second.arguments
            }

    private fun advance(state: RunState): RunState =
        try {
            advanceLoop(state)
        } catch (e: BudgetExceeded) {
            exhausted(state, e)
        }

    private fun exhausted(
        state: RunState,
        error: BudgetExceeded,
    ): RunState {
        state.status = RunStatus.BUDGET_EXHAUSTED
        state.error = error.message
        store.save(state)
        onProgress(state, "Лимит исчерпан")
        return state
    }

    private fun advanceLoop(state: RunState): RunState {
        if (state.status != RunStatus.RUNNING) return state
        while (state.status == RunStatus.RUNNING) {
            if (state.batchIndex < state.batch.size) {
                val call = state.batch[state.batchIndex]
                val record = state.calls[call.id]
                if (record != null &&
                    (
                        !sameCall(record.call, call) ||
                            (state.tasksEnabled && state.phase == Phase.EXECUTE && record.taskId != state.batchTaskId)
                    )
                ) {
                    appendResult(
                        state,
                        call,
                        boundedResult(state, failure("Protocol error: tool_call_id reused with different tool or arguments")),
                    )
                    continue
                }
                val current =
                    record ?: CallRecord(call, taskId = state.batchTaskId, phase = state.phase).also {
                        state.calls[call.id] = it
                        if (state.phase == Phase.PLAN) state.planningCallIds += call.id
                        store.save(state)
                    }
                if (current.status == CallStatus.STARTED) {
                    state.status = RunStatus.NEEDS_RECOVERY
                    store.save(state)
                    return state
                }
                if (current.status == CallStatus.DONE) {
                    appendResult(state, call, requireNotNull(current.result))
                    continue
                }
                if (current.status == CallStatus.WAITING_CONFIRMATION) {
                    state.status = RunStatus.WAITING_ACTION
                    store.save(state)
                    return state
                }
                budgets.resultAllowance(state)
                val policy: Policy
                val checked: CheckedAction
                try {
                    policy = Policy(Path.of(state.workspace), Path.of(state.policyPath))
                    require(!store.directory.startsWith(policy.root)) { "State directory must be outside workspace" }
                    if (state.tasksEnabled && state.phase == Phase.EXECUTE) taskManager.check(state, call)
                    checked = policy.check(call, state)
                } catch (e: Exception) {
                    complete(state, current, failure(e.message ?: "Invalid tool call"))
                    continue
                }
                if (call.name == "submit_plan") {
                    try {
                        val planValue = Schemas.arguments(call, state.tasksEnabled)
                        val tasks = if (state.tasksEnabled) taskManager.fromPlan(planValue) else emptyList()
                        state.plan = planValue
                        state.tasks.clear()
                        state.tasks.addAll(tasks)
                        complete(state, current, success("status" to JsonPrimitive("plan_saved")))
                    } catch (e: BudgetExceeded) {
                        throw e
                    } catch (e: Exception) {
                        complete(state, current, failure(e.message ?: "Invalid task plan"))
                    }
                    continue
                }
                if (call.name == "complete_task") {
                    try {
                        taskManager.complete(state, Schemas.arguments(call))
                        complete(state, current, success("status" to JsonPrimitive("task_completed")))
                    } catch (e: BudgetExceeded) {
                        throw e
                    } catch (e: Exception) {
                        complete(state, current, failure(e.message ?: "Task verification failed"))
                    }
                    continue
                }
                if (checked.confirmation && !current.approved) {
                    current.status = CallStatus.WAITING_CONFIRMATION
                    state.status = RunStatus.WAITING_ACTION
                    store.save(state)
                    return state
                }
                budgets.attempt(state, current)
                current.status = CallStatus.STARTED
                current.attempts++
                store.save(state)
                onProgress(state, "Инструмент ${call.name}")
                afterBudgetDebit(call.name)
                val result =
                    try {
                        toolExecutor.execute(call, checked, policy, budgets.resultAllowance(state))
                    } catch (e: Exception) {
                        failure(e.message ?: "Tool failed")
                    }
                afterExecute(call)
                complete(state, current, result)
                continue
            }
            state.batch = emptyList()
            state.batchIndex = 0
            if (state.phase == Phase.PLAN && state.plan != null) {
                state.status = RunStatus.WAITING_PLAN
                store.save(state)
                return state
            }
            if (state.tasksEnabled && state.phase == Phase.EXECUTE) {
                taskManager.activate(state)
                store.save(state)
                onProgress(state, "Состояние задач")
            }
            budgets.modelRequest(state)
            store.save(state)
            onProgress(state, "Запрос к модели")
            afterBudgetDebit("model")
            val reply =
                try {
                    model.respond(
                        state.messages.toList(),
                        Schemas.tools
                            .map {
                                if (state.tasksEnabled && state.phase == Phase.PLAN &&
                                    it.name == "submit_plan"
                                ) {
                                    Schemas.taskPlan
                                } else {
                                    it
                                }
                            }.filter {
                                if (state.phase ==
                                    Phase.PLAN
                                ) {
                                    it.name in setOf("list_files", "read_file", "submit_plan")
                                } else {
                                    it.name != "submit_plan" && (it.name != "complete_task" || state.tasksEnabled) &&
                                        (!state.tasksEnabled || state.currentTaskId != null)
                                }
                            },
                    )
                } catch (e: Exception) {
                    state.modelRequestPending = false
                    state.error = e.message ?: "Model request failed"
                    store.save(state)
                    return state
                }
            state.modelRequestPending = false
            require(reply.role == "assistant") { "Model must return an assistant message" }
            state.error = null
            if (reply.toolCalls.isEmpty()) {
                state.messages += reply
                if (state.phase == Phase.PLAN) {
                    state.status = RunStatus.FAILED
                    state.error = "Plan Mode ended without a structured submit_plan result"
                } else if (state.tasksEnabled && state.tasks.any { it.status != TaskStatus.COMPLETED }) {
                    taskManager.failCurrent(state)
                    state.status = RunStatus.FAILED
                    state.error = "Task execution ended without verified completion of every task"
                } else {
                    state.status = RunStatus.COMPLETED
                }
                store.save(state)
                return state
            }
            if (state.tasksEnabled && state.phase == Phase.EXECUTE && state.currentTaskId == null) {
                state.status = RunStatus.FAILED
                state.error = "No active task: model must return final text without tools"
                store.save(state)
                return state
            }
            require(reply.toolCalls.all { it.id.isNotBlank() }) { "Empty tool_call_id" }
            state.messages += reply
            try {
                budgets.batch(state, reply.toolCalls)
            } catch (e: BudgetExceeded) {
                return exhausted(state, e)
            }
            state.batchTaskId = if (state.phase == Phase.EXECUTE && state.tasksEnabled) state.currentTaskId else null
            state.batch = reply.toolCalls
            state.batchIndex = 0
            store.save(state)
        }
        return state
    }

    private fun complete(
        state: RunState,
        record: CallRecord,
        result: String,
    ) {
        saveResult(state, record, result)
        store.save(state)
        onProgress(state, "Результат ${record.call.name}")
        appendResult(state, record.call, requireNotNull(record.result))
    }

    private fun boundedResult(
        state: RunState,
        result: String,
    ): String {
        val bounded = resultLimiter.limit(result, budgets.resultAllowance(state))
        state.budget.used.resultBytes += bounded.toByteArray(Charsets.UTF_8).size
        return bounded
    }

    private fun saveResult(
        state: RunState,
        record: CallRecord,
        result: String,
    ) {
        record.result = boundedResult(state, result)
        record.status = CallStatus.DONE
    }

    private fun appendResult(
        state: RunState,
        call: ToolCall,
        result: String,
    ) {
        state.messages += Message("tool", result, toolCallId = call.id)
        state.batchIndex++
        store.save(state)
    }
}
