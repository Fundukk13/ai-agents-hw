package edu.hse.agent.llm

import com.sun.net.httpserver.HttpServer
import edu.hse.agent.models.Message
import edu.hse.agent.tools.Schemas
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetSocketAddress

class ModelClientResponseTest {
    private fun response(
        body: String,
        action: (CompatibleModelClient) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readAllBytes() }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            action(CompatibleModelClient("http://127.0.0.1:${server.address.port}/v1", "test", "model"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `final Unicode answer with null calls is decoded`() {
        response("""{"choices":[{"message":{"role":"assistant","content":"Готово 🌍","tool_calls":null,"reasoning":null}}]}""") {
            val answer = it.respond(listOf(Message("user", "task")), Schemas.tools)
            assertEquals("Готово 🌍", answer.content)
            assertTrue(answer.toolCalls.isEmpty())
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "not JSON",
            "{}",
            "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"role\":\"user\",\"content\":\"wrong role\"}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"x\",\"type\":\"unsupported\"}]}}]}",
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"function\":{}}]}}]}",
        ],
    )
    fun `malformed responses are rejected instead of treated as completion`(body: String) {
        response(body) { client -> assertThrows(Exception::class.java) { client.respond(emptyList(), Schemas.tools) } }
    }

    @Test
    fun `blank model or key is rejected before network request`() {
        assertThrows(IllegalArgumentException::class.java) { CompatibleModelClient("http://127.0.0.1:1/v1", "", "model") }
        assertThrows(IllegalArgumentException::class.java) { CompatibleModelClient("http://127.0.0.1:1/v1", "key", " ") }
    }
}
