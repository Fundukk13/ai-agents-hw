package edu.hse.agent.tools.implementations

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.policy.Policy
import edu.hse.agent.texts.Prompts
import edu.hse.agent.tools.Tool
import edu.hse.agent.tools.objectSchema
import edu.hse.agent.tools.success
import edu.hse.agent.tools.textSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files

class ListFilesTool : Tool {
    override val definition =
        ToolDefinition(
            "list_files",
            Prompts.toolDescription("list_files"),
            objectSchema("path" to textSchema()),
            true,
        )

    override fun execute(
        arguments: JsonObject,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int,
    ): String {
        val entries =
            Files.list(requireNotNull(checked.path)).use { stream ->
                stream
                    .filter { runCatching { policy.resolve(policy.root.relativize(it).toString()) }.isSuccess }
                    .sorted()
                    .limit(minOf(1001, maxResultBytes + 1L))
                    .map { policy.root.relativize(it).toString() }
                    .toList()
            }
        return success(
            "files" to JsonArray(entries.take(1000).map(::JsonPrimitive)),
            "truncated" to JsonPrimitive(entries.size > 1000 || entries.size > maxResultBytes),
        )
    }
}
