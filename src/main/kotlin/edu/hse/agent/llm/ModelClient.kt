package edu.hse.agent.llm

import edu.hse.agent.models.Message
import edu.hse.agent.models.ToolDefinition

fun interface ModelClient {
    fun respond(
        messages: List<Message>,
        tools: List<ToolDefinition>,
    ): Message
}
