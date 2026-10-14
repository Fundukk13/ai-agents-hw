package edu.hse.agent.cli

internal class CliArguments(
    args: Array<String>,
) {
    val command = args.firstOrNull() ?: "help"
    val options = mutableMapOf<String, String>()
    val positional = mutableListOf<String>()

    init {
        val flags = setOf("plan", "tasks")
        val values = setOf("task", "mode", "workspace", "policy", "state-dir", "reason", "choice", "crash-after", "budget")
        var i = 1
        while (i < args.size) {
            val value = args[i++]
            if (!value.startsWith("--")) {
                positional += value
                continue
            }
            val key = value.removePrefix("--")
            require(key !in options) { "Duplicate option: $value" }
            options[key] =
                when (key) {
                    in flags -> {
                        "true"
                    }

                    in values -> {
                        require(i < args.size) { "Missing value for $value" }
                        args[i++]
                    }

                    else -> {
                        error("Unknown option: $value")
                    }
                }
        }
    }

    fun id(): String {
        require(positional.size == 1) { "Exactly one RUN_ID is required" }
        return positional.single()
    }
}
