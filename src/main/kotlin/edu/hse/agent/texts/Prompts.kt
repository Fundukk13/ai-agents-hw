package edu.hse.agent.texts

import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import kotlinx.serialization.json.jsonObject

object Prompts {
    val defaultTask: String = TextResources.read("prompts/default-task.txt")
    val planning: String = TextResources.read("prompts/planning.txt")
    val execution: String = TextResources.read("prompts/execution.txt")
    val taskMode: String = TextResources.read("prompts/task-mode.txt")
    val planApproved: String = TextResources.read("prompts/plan-approved.txt")
    val tasksCompleted: String = TextResources.read("prompts/tasks-completed.txt")
    private val systemTemplate = PromptTemplate(TextResources.read("prompts/system.txt"))
    private val rejectionTemplate = PromptTemplate(TextResources.read("prompts/plan-rejected.txt"))
    private val currentTaskTemplate = PromptTemplate(TextResources.read("prompts/current-task.txt"))
    private val descriptions = json.parseToJsonElement(TextResources.read("prompts/tool-descriptions.json")).jsonObject

    fun system(
        phaseInstruction: String,
        allowedCommands: List<String>,
        deniedDirectories: List<String>,
    ): String =
        systemTemplate.render(
            mapOf(
                "phase_instruction" to phaseInstruction,
                "allowed_commands" to allowedCommands.toString(),
                "denied_directories" to deniedDirectories.toString(),
            ),
        )

    fun planRejected(reason: String): String = rejectionTemplate.render(mapOf("reason" to reason))

    fun currentTask(taskJson: String): String = currentTaskTemplate.render(mapOf("task_json" to taskJson))

    fun toolDescription(name: String): String = descriptions.string(name)
}
