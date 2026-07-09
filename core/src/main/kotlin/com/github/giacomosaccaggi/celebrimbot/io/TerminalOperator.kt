package com.github.giacomosaccaggi.celebrimbot.io

import java.util.concurrent.CompletableFuture

/**
 * The result of a terminal command execution.
 * Both stdout and stderr are merged into [output] (mirrors ProcessBuilder.redirectErrorStream).
 * [exitCode] is -1 if the process timed out or could not be started.
 */
data class TerminalResult(val output: String, val exitCode: Int)

/**
 * Interface for executing terminal commands.
 */
interface TerminalOperator {
    fun executeCommand(command: String): CompletableFuture<TerminalResult>
}
