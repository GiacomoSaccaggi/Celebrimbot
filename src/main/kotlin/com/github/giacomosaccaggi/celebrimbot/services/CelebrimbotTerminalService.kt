package com.github.giacomosaccaggi.celebrimbot.services

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class CelebrimbotTerminalService(private val project: Project) {

    fun executeCommand(command: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        val output = StringBuilder()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Simple space-based splitting (might need refinement for quoted strings, 
                // but matches GeneralCommandLine expectations for basic command-line construction)
                val commandLine = GeneralCommandLine(command.split(" "))
                    .withWorkDirectory(project.basePath)

                val handler = OSProcessHandler(commandLine)
                handler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        output.append(event.text)
                    }
                })

                handler.startNotify()

                // Wait for the process with a 30-second timeout
                val finished = handler.process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    handler.destroyProcess()
                    output.append("\n[TIMEOUT WARNING: Process killed after 30 seconds]")
                }
                
                future.complete(output.toString())
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
