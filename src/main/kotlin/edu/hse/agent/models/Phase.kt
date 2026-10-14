package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
enum class Phase {
    PLAN,
    EXECUTE,
}
