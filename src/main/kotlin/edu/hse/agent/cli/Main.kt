package edu.hse.agent.cli

import edu.hse.agent.runtime.SimulatedCrash
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val renderer = ConsoleRenderer()
    val exitCode =
        try {
            val command = CommandParser().parse(args)
            renderer.render(command.execute(CommandContext()))
        } catch (e: SimulatedCrash) {
            renderer.renderError(e.message, 86)
        } catch (e: Exception) {
            renderer.renderError("Error: ${e.message}", 1)
        }
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
