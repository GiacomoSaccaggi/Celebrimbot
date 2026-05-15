package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.io.TerminalResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CelebrimbotTerminalService(private val project: Project) {

    fun executeCommand(command: String): CompletableFuture<TerminalResult> {
        val future = CompletableFuture<TerminalResult>()
        val output = StringBuilder()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val commandLine = GeneralCommandLine(command.split(" "))
                    .withWorkDirectory(project.basePath)

                val handler = OSProcessHandler(commandLine)
                handler.addProcessListener(object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        output.append(event.text)
                    }
                    override fun startNotified(event: ProcessEvent) {}
                    override fun processTerminated(event: ProcessEvent) {}
                })

                handler.startNotify()

                val finished = handler.process.waitFor(30, TimeUnit.SECONDS)
                val exitCode = if (finished) handler.process.exitValue() else {
                    handler.destroyProcess()
                    output.append("\n[TIMEOUT WARNING: Process killed after 30 seconds]")
                    -1
                }

                future.complete(TerminalResult(output.toString(), exitCode))
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }

        return future
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotTerminalService = project.service()
    }
}
