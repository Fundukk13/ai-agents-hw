package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
enum class CallStatus {
    PENDING,
    WAITING_CONFIRMATION,
    STARTED,
    DONE,
}
