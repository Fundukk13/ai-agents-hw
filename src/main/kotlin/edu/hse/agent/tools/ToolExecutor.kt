package edu.hse.agent.tools

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.ToolCall
import edu.hse.agent.policy.Policy

class ToolExecutor {
    fun execute(
        call: ToolCall,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int = Int.MAX_VALUE,
    ): String {
        val arguments = Schemas.arguments(call)
        return ToolRegistry.find(call.name).execute(arguments, checked, policy, maxResultBytes)
    }
}
