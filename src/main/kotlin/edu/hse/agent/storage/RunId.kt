package edu.hse.agent.storage

import java.util.UUID

internal object RunId {
    fun validate(id: String) {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid run_id" }
    }
}
