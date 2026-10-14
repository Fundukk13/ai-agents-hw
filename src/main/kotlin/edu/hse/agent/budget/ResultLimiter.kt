package edu.hse.agent.budget

import edu.hse.agent.serialization.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ResultLimiter {
    private val compact = Json { prettyPrint = false }

    fun limit(
        result: String,
        allowance: Int,
    ): String {
        if (result.toByteArray(Charsets.UTF_8).size <= allowance) return result
        val value = json.parseToJsonElement(result)
        val original = value as? JsonObject ?: error("Tool result must be an object")

        fun shorten(
            value: JsonElement,
            characters: Int,
        ): JsonElement =
            when (value) {
                is JsonObject -> JsonObject(value.mapValues { shorten(it.value, characters) })
                is JsonArray -> JsonArray(value.take(characters).map { shorten(it, characters) })
                is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.take(characters)) else value
            }

        fun candidate(characters: Int): String =
            compact.encodeToString(
                JsonObject((shorten(original, characters) as JsonObject) + ("truncated" to JsonPrimitive(true))),
            )
        val minimum = candidate(0)
        if (minimum.toByteArray(Charsets.UTF_8).size > allowance) return "{\"truncated\":true}"
        var low = 0
        var high = result.length
        while (low < high) {
            val middle = low + (high - low + 1) / 2
            if (candidate(middle).toByteArray(Charsets.UTF_8).size <= allowance) low = middle else high = middle - 1
        }
        return candidate(low)
    }
}
