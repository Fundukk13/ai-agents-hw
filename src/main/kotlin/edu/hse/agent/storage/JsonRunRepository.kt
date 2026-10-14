package edu.hse.agent.storage

import edu.hse.agent.models.RunState
import edu.hse.agent.serialization.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

class JsonRunRepository(
    directory: Path,
) : RunRepository {
    override val directory: Path = Files.createDirectories(directory).toRealPath()

    private fun path(id: String): Path {
        RunId.validate(id)
        return directory.resolve("$id.json")
    }

    override fun save(state: RunState) {
        val destination = path(state.runId)
        val temporary = Files.createTempFile(directory, ".state-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(json.encodeToString(state).toByteArray(Charsets.UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)

            runCatching { FileChannel.open(directory, READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun load(id: String): RunState =
        json
            .decodeFromJsonElement<RunState>(
                JsonObject(json.parseToJsonElement(Files.readString(path(id))).jsonObject - "demo"),
            ).also {
                require(it.version == 1 && it.runId == id) { "Unsupported or mismatched state" }
            }

    override fun listIds(): List<String> =
        Files.list(directory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith(".json") }
                .map { it.fileName.toString().removeSuffix(".json") }
                .filter { runCatching { RunId.validate(it) }.isSuccess }
                .sorted()
                .toList()
        }

    override fun <T> locked(
        id: String,
        action: () -> T,
    ): T = RunFileLock(directory).locked(id, action)
}
