package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class Rules(
    val allow: List<String>,
    val deny: List<String>,
)
