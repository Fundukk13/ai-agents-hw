package edu.hse.agent.tools

import edu.hse.agent.serialization.json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun success(vararg fields: Pair<String, JsonElement>): String =
    json.encodeToString(
        buildJsonObject {
            put("ok", true)
            fields.forEach { (name, value) -> put(name, value) }
        },
    )

fun failure(reason: String): String =
    json.encodeToString(
        buildJsonObject {
            put("ok", false)
            put("error", reason)
        },
    )
