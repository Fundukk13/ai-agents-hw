package edu.hse.agent.texts

internal object TextResources {
    fun read(path: String): String =
        requireNotNull(javaClass.getResourceAsStream("/$path")) { "Missing text resource: $path" }
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText().trimEnd('\r', '\n') }
}
