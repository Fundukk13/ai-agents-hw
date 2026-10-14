package edu.hse.agent.tools.implementations

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.ToolDefinition
import edu.hse.agent.policy.Policy
import edu.hse.agent.texts.Prompts
import edu.hse.agent.tools.Tool
import edu.hse.agent.tools.objectSchema
import edu.hse.agent.tools.success
import edu.hse.agent.tools.textSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class BashTool : Tool {
    override val definition =
        ToolDefinition(
            "Bash",
            Prompts.toolDescription("Bash"),
            objectSchema("command" to textSchema(), "cwd" to textSchema()),
        )

    override fun execute(
        arguments: JsonObject,
        checked: CheckedAction,
        policy: Policy,
        maxResultBytes: Int,
    ): String {
        val builder =
            ProcessBuilder("/bin/bash", "--noprofile", "--norc", "-c", requireNotNull(checked.command))
                .directory(requireNotNull(checked.path).toFile())

        builder.environment().clear()
        builder.environment().putAll(
            mapOf(
                "PATH" to "/usr/bin:/bin:/usr/local/bin:/opt/homebrew/bin",
                "PYTHONDONTWRITEBYTECODE" to "1",
                "LC_ALL" to "C.UTF-8",
            ),
        )
        val process = builder.start()
        process.outputStream.close()
        val remaining = AtomicInteger(minOf(policy.config.maxOutputBytes, maxResultBytes))
        val truncated = AtomicBoolean(false)

        fun capture(
            input: java.io.InputStream,
            output: ByteArrayOutputStream,
        ) = thread(isDaemon = true) {
            input.use {
                val buffer = ByteArray(4096)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    val available = remaining.getAndUpdate { old -> maxOf(0, old - count) }
                    val kept = minOf(count, available)
                    if (kept < count) truncated.set(true)
                    synchronized(output) { output.write(buffer, 0, kept) }
                }
            }
        }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = capture(process.inputStream, stdout)
        val errThread = capture(process.errorStream, stderr)
        val timedOut = !process.waitFor(policy.config.bashTimeoutMillis, TimeUnit.MILLISECONDS)
        if (timedOut) {
            process
                .descendants()
                .toList()
                .asReversed()
                .forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
        outThread.join(2000)
        errThread.join(2000)
        val incomplete = outThread.isAlive || errThread.isAlive

        fun contents(stream: ByteArrayOutputStream) = synchronized(stream) { stream.toString(Charsets.UTF_8) }
        return success(
            "stdout" to JsonPrimitive(contents(stdout)),
            "stderr" to JsonPrimitive(contents(stderr)),
            "exit_code" to (if (process.isAlive) JsonNull else JsonPrimitive(process.exitValue())),
            "timed_out" to JsonPrimitive(timedOut),
            "truncated" to JsonPrimitive(truncated.get() || incomplete),
        )
    }
}
