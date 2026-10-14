package edu.hse.agent.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class RunState(
    val version: Int = 1,
    val runId: String = UUID.randomUUID().toString(),
    val workspace: String,
    val policyPath: String,
    val mode: Mode,
    var phase: Phase = Phase.EXECUTE,
    var status: RunStatus = RunStatus.RUNNING,
    val messages: MutableList<Message> = mutableListOf(),
    val calls: MutableMap<String, CallRecord> = linkedMapOf(),
    var batch: List<ToolCall> = emptyList(),
    var batchIndex: Int = 0,
    var plan: JsonObject? = null,
    val decisions: MutableList<Decision> = mutableListOf(),
    val budget: BudgetState = BudgetState(),
    var modelRequestPending: Boolean = false,
    val tasksEnabled: Boolean = false,
    val tasks: MutableList<AgentTask> = mutableListOf(),
    val planningCallIds: MutableList<String> = mutableListOf(),
    var currentTaskId: String? = null,
    var batchTaskId: String? = null,
    var taskFinalRequested: Boolean = false,
    var error: String? = null,
)
