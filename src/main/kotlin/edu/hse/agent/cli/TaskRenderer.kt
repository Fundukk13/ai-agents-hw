package edu.hse.agent.cli

import edu.hse.agent.models.AgentTask
import edu.hse.agent.models.TaskStatus

class TaskRenderer {
    fun render(tasks: List<AgentTask>): String =
        tasks.joinToString("\n") { task ->
            val mark =
                when (task.status) {
                    TaskStatus.COMPLETED -> "x"
                    TaskStatus.RUNNING -> ">"
                    TaskStatus.FAILED -> "!"
                    TaskStatus.BLOCKED -> "-"
                    TaskStatus.PENDING -> " "
                }
            val dependencies = if (task.dependencies.isEmpty()) "" else " (после: ${task.dependencies.joinToString()})"
            val evidence = task.result?.let { " [проверка: ${it.tool}, call_id=${it.callId}]" } ?: ""
            "[$mark] ${task.id}: ${task.description}$dependencies$evidence"
        }
}
