package com.github.giacomosaccaggi.celebrimbot.io

import java.util.concurrent.CompletableFuture

/**
 * Interface for executing terminal commands.
 */
interface TerminalOperator {
    fun executeCommand(command: String): CompletableFuture<String>
}
