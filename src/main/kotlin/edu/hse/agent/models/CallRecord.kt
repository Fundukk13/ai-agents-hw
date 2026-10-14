package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class CallRecord(
    val call: ToolCall,
    var status: CallStatus = CallStatus.PENDING,
    var result: String? = null,
    var approved: Boolean = false,
    var attempts: Int = 0,
    var retryPending: Boolean = false,
    val taskId: String? = null,
    val phase: Phase = Phase.EXECUTE,
)
