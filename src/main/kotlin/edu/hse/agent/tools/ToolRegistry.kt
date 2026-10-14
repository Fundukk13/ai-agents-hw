package edu.hse.agent.tools

import edu.hse.agent.tools.implementations.BashTool
import edu.hse.agent.tools.implementations.DeleteFileTool
import edu.hse.agent.tools.implementations.ListFilesTool
import edu.hse.agent.tools.implementations.ReadFileTool
import edu.hse.agent.tools.implementations.WriteFileTool

object ToolRegistry {
    private val tools =
        listOf(ListFilesTool(), ReadFileTool(), WriteFileTool(), DeleteFileTool(), BashTool()).associateBy {
            it.definition.name
        }

    val definitions = tools.values.map { it.definition }

    fun find(name: String): Tool = tools[name] ?: error("No executor for $name")
}
