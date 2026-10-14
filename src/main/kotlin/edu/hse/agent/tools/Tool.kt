package edu.hse.agent.tools

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.policy.Policy
import kotlinx.serialization.json.JsonObject

interface Tool {
    val definition: ToolDefinition

    fun execute(
        arguments: JsonObject,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int = Int.MAX_VALUE,
    ): String
}
