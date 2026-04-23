package com.github.giacomosaccaggi.celebrimbot.io

import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotTerminalService
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

/**
 * IDE implementation of TerminalOperator.
 */
class IdeTerminalOperator(private val project: Project) : TerminalOperator {
    private val terminalService = CelebrimbotTerminalService.getInstance(project)

    override fun executeCommand(command: String): CompletableFuture<String> {
        return terminalService.executeCommand(command)
    }
}
