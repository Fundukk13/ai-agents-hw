package edu.hse.agent.storage

import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

internal class RunFileLock(
    private val directory: Path,
) {
    fun <T> locked(
        id: String,
        action: () -> T,
    ): T {
        RunId.validate(id)
        FileChannel.open(directory.resolve("$id.lock"), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Run is already being used")
            lock.use { return action() }
        }
    }
}
