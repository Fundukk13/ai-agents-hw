package edu.hse.agent.policy

import edu.hse.agent.models.CheckedAction
import edu.hse.agent.models.Mode
import edu.hse.agent.models.Phase
import edu.hse.agent.models.PolicyConfig
import edu.hse.agent.models.RunState
import edu.hse.agent.models.ToolCall
import edu.hse.agent.serialization.json
import edu.hse.agent.serialization.string
import edu.hse.agent.tools.Schemas
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class Policy(
    workspace: Path,
    policyFile: Path,
) {
    val root = workspace.toRealPath()
    val file = policyFile.toRealPath()

    init {
        require(!file.startsWith(root)) { "Policy must be outside workspace" }
    }

    val config =
        json.decodeFromString<PolicyConfig>(Files.readString(file)).also {
            require(it.bashTimeoutMillis in 1..300_000 && it.maxOutputBytes in 1..10_485_760 && it.maxFileBytes in 1..10_485_760) {
                "Invalid policy limits"
            }
        }
    private val allow = config.directories.allow.map(::configPath)
    private val deny = config.directories.deny.map(::configPath)

    private fun configPath(value: String): Path {
        val input = Path.of(value)
        require(input.none { it.toString() == ".." }) { "Policy directory contains .." }
        val path = (if (input.isAbsolute) input else root.resolve(input)).normalize()
        require(path.startsWith(root)) { "Policy directory must be inside workspace" }
        checkSymlinks(path)
        return path
    }

    private fun checkSymlinks(path: Path) {
        var current = root
        for (component in root.relativize(path)) {
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) { "Symlinks are forbidden: $component" }
        }
    }

    fun resolve(value: String): Path {
        val input = Path.of(value)
        require(!input.isAbsolute && input.none { it.toString() == ".." }) { "Only relative paths without .. are allowed" }
        val path = root.resolve(input).normalize()
        require(path.startsWith(root)) { "Path leaves workspace" }
        checkSymlinks(path)
        require(allow.any { path.startsWith(it) } && deny.none { path.startsWith(it) }) { "Directory forbidden by policy" }
        if (Files.exists(path, NOFOLLOW_LINKS)) require(path.toRealPath() == path) { "Noncanonical path" }
        return path
    }

    fun check(
        call: ToolCall,
        state: RunState,
    ): CheckedAction {
        val args = Schemas.arguments(call, state.tasksEnabled)
        val readOnly = call.name in setOf("list_files", "read_file")
        if (state.phase ==
            Phase.PLAN
        ) {
            require(readOnly || call.name == "submit_plan") { "Plan Mode permits only reading and submit_plan; Bash is forbidden" }
        }
        if (call.name == "submit_plan") {
            require(state.phase == Phase.PLAN) { "submit_plan is only available in Plan Mode" }
            args.getValue("steps").let { steps ->
                steps.jsonArray.forEach { step ->
                    step.jsonObject
                        .getValue("tools")
                        .jsonArray
                        .forEach { Schemas.definition(it.jsonPrimitive.content) }
                }
            }
            return CheckedAction()
        }
        if (call.name == "complete_task") {
            require(state.tasksEnabled && state.phase == Phase.EXECUTE) { "complete_task requires Task Mode execution" }
            return CheckedAction()
        }
        val path = resolve(args.string(if (call.name == "Bash") "cwd" else "path"))
        when (call.name) {
            "list_files", "Bash" -> {
                require(Files.isDirectory(path, NOFOLLOW_LINKS)) { "Directory does not exist" }
            }

            "read_file", "delete_file" -> {
                require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Regular file does not exist" }
            }

            "write_file" -> {
                require(path != root && Files.isDirectory(path.parent, NOFOLLOW_LINKS)) { "Parent directory does not exist" }
                require(!Files.exists(path, NOFOLLOW_LINKS) || Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Can only write regular files" }
                require(args.string("content").toByteArray().size <= config.maxFileBytes) { "File exceeds policy limit" }
            }
        }
        val command = if (call.name == "Bash") args.string("command") else null
        if (command != null) {
            require(command !in config.commands.deny && command in config.commands.allow) { "Command forbidden by policy" }

            require(Regex("[A-Za-z0-9_-]+(?: [A-Za-z0-9_-]+)*").matches(command)) {
                "Only simple commands without shell syntax or path arguments are allowed"
            }
            val tokens = command.split(' ')
            require(tokens.first() !in setOf("bash", "sh", "zsh", "env", "eval", "exec", "source")) { "Nested shell execution forbidden" }
            require(tokens.none { it in setOf("-c", "--command", "-e", "--eval") }) { "Inline code execution forbidden" }
        }
        return CheckedAction(path, command, call.name == "delete_file" || (!readOnly && state.mode == Mode.SAFE))
    }
}
