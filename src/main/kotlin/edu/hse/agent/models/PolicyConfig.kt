package edu.hse.agent.models

import kotlinx.serialization.Serializable

@Serializable
data class PolicyConfig(
    val commands: Rules,
    val directories: Rules,
    val bashTimeoutMillis: Long = 10_000,
    val maxOutputBytes: Int = 65_536,
    val maxFileBytes: Int = 1_048_576,
)
