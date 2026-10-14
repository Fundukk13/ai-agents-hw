package edu.hse.agent.llm

import edu.hse.agent.models.Message
import edu.hse.agent.models.ToolCall
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CompatibleModelClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val reasoningEffort: String? = null,
) : ModelClient {
    init {
        require(model.isNotBlank() && apiKey.isNotBlank()) { "OPENAI_MODEL and OPENAI_API_KEY are required" }
    }

    override fun respond(
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): Message {
        val body =
            buildJsonObject {
                put("model", model)
                put("messages", JsonArray(messages.map(::wireMessage)))
                put(
                    "tools",
                    JsonArray(
                        tools.map { tool ->
                            buildJsonObject {
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", tool.name)
                                        put("description", tool.description)
                                        put("parameters", tool.schema)
                                    },
                                )
                            }
                        },
                    ),
                )
                put("tool_choice", "auto")
                reasoningEffort?.let { put("reasoning_effort", it) }
            }
        val request =
            HttpRequest
                .newBuilder(URI.create(baseUrl.trimEnd('/') + "/chat/completions"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString(body)))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())

        require(response.statusCode() in 200..299) { "Model API HTTP ${response.statusCode()}" }
        val message =
            json
                .parseToJsonElement(response.body())
                .jsonObject
                .getValue("choices")
                .jsonArray
                .first()
                .jsonObject
                .getValue("message")
                .jsonObject
        require(message.string("role") == "assistant") { "Expected assistant message" }
        val calls =
            message["tool_calls"]
                ?.takeUnless { it == JsonNull }
                ?.jsonArray
                ?.map { item ->
                    val call = item.jsonObject
                    require(call.string("type") == "function") { "Unsupported tool call type" }
                    val function = call.getValue("function").jsonObject
                    ToolCall(call.string("id"), function.string("name"), function.string("arguments"))
                }.orEmpty()
        return Message(
            role = "assistant",
            content = message["content"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
            toolCalls = calls,
            reasoning = message["reasoning"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content,
        )
    }

    private fun wireMessage(message: Message) =
        buildJsonObject {
            put("role", message.role)
            put("content", message.content?.let(::JsonPrimitive) ?: JsonNull)
            message.toolCallId?.let { put("tool_call_id", it) }
            message.reasoning?.let { put("reasoning", it) }
            if (message.toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JsonArray(
                        message.toolCalls.map { call ->
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    },
                                )
                            }
                        },
                    ),
                )
            }
        }
}
