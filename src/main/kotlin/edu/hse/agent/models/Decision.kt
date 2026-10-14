package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class Decision(
    val kind: String,
    val target: String,
    val choice: String,
    val reason: String,
)
