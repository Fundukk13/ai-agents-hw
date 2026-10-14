package edu.hse.agent.storage

import edu.hse.agent.models.RunState
import java.nio.file.Path

interface RunRepository {
    val directory: Path

    fun save(state: RunState)

    fun load(id: String): RunState

    fun listIds(): List<String>

    fun <T> locked(
        id: String,
        action: () -> T,
    ): T
}
