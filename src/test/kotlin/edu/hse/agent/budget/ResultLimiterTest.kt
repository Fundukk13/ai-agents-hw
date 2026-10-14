package edu.hse.agent.budget

import edu.hse.agent.serialization.json
import edu.hse.agent.tools.success
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ResultLimiterTest {
    private val limiter = ResultLimiter()

    @ParameterizedTest
    @ValueSource(ints = [18, 32, 64, 128, 512])
    fun `nested Unicode and escaped text stay valid JSON within byte allowance`(allowance: Int) {
        val result =
            success(
                "content" to JsonPrimitive("Привет 🌍 \n\t\"".repeat(1000)),
                "files" to JsonArray((1..100).map { JsonObject(mapOf("path" to JsonPrimitive("папка/$it.txt"))) }),
            )
        val limited = limiter.limit(result, allowance)
        assertTrue(limited.toByteArray(Charsets.UTF_8).size <= allowance)
        assertTrue(
            json
                .parseToJsonElement(limited)
                .jsonObject
                .getValue("truncated")
                .jsonPrimitive.boolean,
        )
    }

    @Test
    fun `result exactly at byte boundary is returned unchanged`() {
        val result = success("content" to JsonPrimitive("Привет 🌍"))
        assertEquals(result, limiter.limit(result, result.toByteArray(Charsets.UTF_8).size))
    }

    @Test
    fun `truncation retains Bash outcome metadata when allowance permits`() {
        val result =
            success(
                "stdout" to JsonPrimitive("x".repeat(10000)),
                "stderr" to JsonPrimitive("problem".repeat(1000)),
                "exit_code" to JsonPrimitive(7),
                "timed_out" to JsonPrimitive(true),
            )
        val limited = limiter.limit(result, 160)
        val value = json.parseToJsonElement(limited).jsonObject
        assertEquals(7, value.getValue("exit_code").jsonPrimitive.int)
        assertTrue(value.getValue("timed_out").jsonPrimitive.boolean)
        assertTrue(value.getValue("truncated").jsonPrimitive.boolean)
        assertTrue(limited.toByteArray(Charsets.UTF_8).size <= 160)
    }

    @Test
    fun `already truncated result is not made complete`() {
        val result = success("content" to JsonPrimitive("partial"), "truncated" to JsonPrimitive(true))
        assertEquals(result, limiter.limit(result, 1000))
        assertTrue(
            json
                .parseToJsonElement(limiter.limit(result, 18))
                .jsonObject
                .getValue("truncated")
                .jsonPrimitive.boolean,
        )
    }
}
