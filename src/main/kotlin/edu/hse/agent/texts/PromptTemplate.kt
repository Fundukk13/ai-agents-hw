package edu.hse.agent.texts

class PromptTemplate(
    private val template: String,
) {
    fun render(values: Map<String, String>): String =
        Regex("\\{\\{([a-z_]+)}}").replace(template) { match ->
            values[match.groupValues[1]] ?: error("Missing prompt value: ${match.groupValues[1]}")
        }
}
