package edu.hse.agent.tools.implementations

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.policy.Policy
import edu.hse.agent.serialization.string
import edu.hse.agent.tools.Tool
import edu.hse.agent.tools.objectSchema
import edu.hse.agent.tools.success
import edu.hse.agent.tools.textSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class WriteFileTool : Tool {
    override val definition =
        ToolDefinition(
            "write_file",
            "Create or replace a UTF-8 workspace file.",
            objectSchema(
                "path" to textSchema(),
                "content" to textSchema(),
            ),
            true,
        )

    override fun execute(
        arguments: JsonObject,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int,
    ): String {
        val destination = requireNotNull(checked.path)
        val temporary = Files.createTempFile(destination.parent, ".agent-", ".tmp")
        try {
            Files.writeString(temporary, arguments.string("content"))
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return success("path" to JsonPrimitive(arguments.string("path")))
    }
}
