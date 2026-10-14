package edu.hse.agent.models

import java.nio.file.Path

data class CheckedAction(
    val path: Path? = null,
    val command: String? = null,
    val confirmation: Boolean = false,
)
