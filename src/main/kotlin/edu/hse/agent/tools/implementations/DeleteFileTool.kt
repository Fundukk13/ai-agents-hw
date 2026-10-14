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

class DeleteFileTool : Tool {
    override val definition =
        ToolDefinition(
            "delete_file",
            "Delete one regular workspace file. Always requires user confirmation.",
            objectSchema("path" to textSchema()),
            false,
        )

    override fun execute(
        arguments: JsonObject,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int,
    ): String {
        Files.delete(requireNotNull(checked.path))
        return success("deleted" to JsonPrimitive(arguments.string("path")))
    }
}
