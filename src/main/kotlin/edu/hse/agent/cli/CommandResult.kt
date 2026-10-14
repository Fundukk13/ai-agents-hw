package edu.hse.agent.cli

import edu.hse.agent.models.RunState

sealed interface CommandResult {
    data class Text(
        val text: String,
    ) : CommandResult

    data class State(
        val state: RunState,
    ) : CommandResult
}
