package edu.hse.agent.serialization

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

val json =
    Json {
        prettyPrint = true
        encodeDefaults = true
    }

fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
