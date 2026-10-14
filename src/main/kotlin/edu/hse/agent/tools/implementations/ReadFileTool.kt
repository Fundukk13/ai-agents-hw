package edu.hse.agent.tools.implementations

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.policy.Policy
import edu.hse.agent.texts.Prompts
import edu.hse.agent.tools.Tool
import edu.hse.agent.tools.objectSchema
import edu.hse.agent.tools.success
import edu.hse.agent.tools.textSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

class ReadFileTool : Tool {
    override val definition = ToolDefinition("read_file", Prompts.toolDescription("read_file"), objectSchema("path" to textSchema()), true)

    override fun execute(
        arguments: JsonObject,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int,
    ): String {
        val limit = minOf(policy.config.maxFileBytes, policy.config.maxOutputBytes, maxResultBytes)
        val bytes = Files.newInputStream(requireNotNull(checked.path)).use { it.readNBytes(limit + 1) }
        return success(
            "content" to JsonPrimitive(bytes.take(limit).toByteArray().toString(Charsets.UTF_8)),
            "truncated" to JsonPrimitive(bytes.size > limit),
        )
    }
}
