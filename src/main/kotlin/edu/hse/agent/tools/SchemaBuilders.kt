package edu.hse.agent.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun textSchema() = buildJsonObject { put("type", "string") }

internal fun arraySchema(
    items: JsonObject,
    minItems: Int = 1,
) = buildJsonObject {
    put("type", "array")
    put("items", items)
    put("minItems", minItems)
}

internal fun objectSchema(vararg properties: Pair<String, JsonObject>) =
    buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(properties.toMap()))
        put("required", JsonArray(properties.map { JsonPrimitive(it.first) }))
        put("additionalProperties", false)
    }
