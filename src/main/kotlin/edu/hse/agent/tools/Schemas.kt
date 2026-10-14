package edu.hse.agent.tools

import edu.hse.agent.models.ToolCall
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import edu.hse.agent.texts.Prompts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object Schemas {
    val tools =
        ToolRegistry.definitions +
            listOf(
                ToolDefinition(
                    "complete_task",
                    Prompts.toolDescription("complete_task"),
                    objectSchema("task_id" to textSchema(), "call_id" to textSchema()),
                ),
                ToolDefinition(
                    "submit_plan",
                    Prompts.toolDescription("submit_plan"),
                    objectSchema(
                        "goal" to textSchema(),
                        "steps" to arraySchema(objectSchema("description" to textSchema(), "tools" to arraySchema(textSchema()))),
                        "risks" to arraySchema(textSchema()),
                        "assumptions" to arraySchema(textSchema()),
                        "done_when" to textSchema(),
                    ),
                ),
            )

    val taskPlan =
        ToolDefinition(
            "submit_plan",
            Prompts.toolDescription("submit_tasks"),
            objectSchema(
                "goal" to textSchema(),
                "steps" to
                    arraySchema(
                        objectSchema(
                            "id" to textSchema(),
                            "description" to textSchema(),
                            "depends_on" to arraySchema(textSchema(), 0),
                            "tools" to arraySchema(textSchema()),
                            "verification_tool" to textSchema(),
                        ),
                    ),
                "risks" to arraySchema(textSchema()),
                "assumptions" to arraySchema(textSchema()),
                "done_when" to textSchema(),
            ),
        )

    fun definition(name: String) = tools.find { it.name == name } ?: error("Unknown tool: $name")

    fun arguments(
        call: ToolCall,
        tasksEnabled: Boolean = false,
    ): JsonObject {
        val value = json.parseToJsonElement(call.arguments)
        validate(value, (if (tasksEnabled && call.name == "submit_plan") taskPlan else definition(call.name)).schema, "arguments")
        return value.jsonObject
    }

    private fun validate(
        value: JsonElement,
        schema: JsonObject,
        location: String,
    ) {
        when (schema.string("type")) {
            "object" -> {
                require(value is JsonObject) { "$location must be an object" }
                val properties = schema.getValue("properties").jsonObject
                val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
                require(value.keys.containsAll(required)) { "$location missing required fields" }
                require(value.keys.all { it in properties }) { "$location has unexpected fields" }
                value.forEach { (key, child) -> validate(child, properties.getValue(key).jsonObject, "$location.$key") }
            }

            "array" -> {
                require(
                    value is JsonArray && value.size >= (schema["minItems"]?.jsonPrimitive?.int ?: 1),
                ) { "$location has too few array items" }
                value.forEachIndexed { i, child -> validate(child, schema.getValue("items").jsonObject, "$location[$i]") }
            }

            "string" -> {
                require(value is JsonPrimitive && value.isString) { "$location must be a string" }
            }

            else -> {
                error("Unsupported schema type")
            }
        }
    }
}
