package edu.hse.agent.llm

import com.sun.net.httpserver.HttpServer
import edu.hse.agent.models.Message
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import edu.hse.agent.tools.Schemas
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class ModelClientTest {
    @Test fun `HTTP client sends schemas and tool ids and decodes multiple calls`() {
        val request = AtomicReference<JsonObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            request.set(json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject)
            assertEquals("Bearer test-placeholder", exchange.requestHeaders.getFirst("Authorization"))
            val body =
                """{"choices":[{"message":{"role":"assistant","content":null,"reasoning":"response-metadata","tool_calls":[
                {"id":"a","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"order.py\"}"}},
                {"id":"b","type":"function","function":{"name":"list_files","arguments":"{\"path\":\".\"}"}}
            ]}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val messages =
                listOf(
                    Message("user", "Fix"),
                    Message(
                        "assistant",
                        toolCalls = listOf(ToolCall("old", "read_file", "{\"path\":\"order.py\"}")),
                        reasoning = "request-metadata",
                    ),
                    Message("tool", "{\"ok\":true}", toolCallId = "old"),
                )
            val reply =
                CompatibleModelClient(
                    "http://127.0.0.1:${server.address.port}/v1/",
                    "test-placeholder",
                    "test-model",
                    reasoningEffort = "low",
                ).respond(messages, Schemas.tools)
            assertEquals(listOf("a", "b"), reply.toolCalls.map { it.id })
            assertEquals("response-metadata", reply.reasoning)
            val body = request.get()
            assertEquals(
                "old",
                body
                    .getValue("messages")
                    .jsonArray
                    .last()
                    .jsonObject
                    .string("tool_call_id"),
            )
            assertEquals("auto", body.string("tool_choice"))
            assertEquals("low", body.string("reasoning_effort"))
            assertEquals(
                "request-metadata",
                body
                    .getValue("messages")
                    .jsonArray[1]
                    .jsonObject
                    .string("reasoning"),
            )
            assertEquals(Schemas.tools.size, body.getValue("tools").jsonArray.size)
            assertTrue(body.getValue("tools").jsonArray.all { it.jsonObject.getValue("function").jsonObject["parameters"] is JsonObject })
        } finally {
            server.stop(0)
        }
    }

    @Test fun `HTTP errors do not include remote body or key`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            val body = "do not log this body".toByteArray()
            exchange.sendResponseHeaders(401, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    CompatibleModelClient(
                        "http://127.0.0.1:${server.address.port}/v1",
                        "test-placeholder",
                        "model",
                    ).respond(emptyList(), Schemas.tools)
                }
            assertEquals("Model API HTTP 401", error.message)
        } finally {
            server.stop(0)
        }
    }
}
