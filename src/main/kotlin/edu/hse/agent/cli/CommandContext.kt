package edu.hse.agent.cli

import edu.hse.agent.llm.CompatibleModelClient
import edu.hse.agent.runtime.AgentLoop
import edu.hse.agent.runtime.SimulatedCrash
import edu.hse.agent.storage.RunRepository
import edu.hse.agent.storage.SqliteRunRepository
import java.nio.file.Path

class CommandContext(
    private val environment: (String) -> String? = System::getenv,
    private val output: (String) -> Unit = ::println,
) {
    fun store(directory: Path): RunRepository = SqliteRunRepository(directory)

    fun createRuntime(
        directory: Path,
        crashAfter: String?,
    ): AgentLoop {
        val model =
            CompatibleModelClient(
                environment("OPENAI_BASE_URL") ?: error("Set OPENAI_BASE_URL"),
                environment("OPENAI_API_KEY") ?: error("Set OPENAI_API_KEY"),
                environment("OPENAI_MODEL") ?: error("Set OPENAI_MODEL"),
                reasoningEffort = environment("OPENAI_REASONING_EFFORT"),
            )
        return AgentLoop(
            store(directory),
            model,
            afterExecute = { if (it.name == crashAfter) throw SimulatedCrash() },
            onCreated = { output("run_id=${it.runId}") },
            onProgress = { state, event ->
                output("$event\n${BudgetRenderer().render(state.budget)}")
                if (state.tasks.isNotEmpty()) output(TaskRenderer().render(state.tasks))
            },
        )
    }
}
