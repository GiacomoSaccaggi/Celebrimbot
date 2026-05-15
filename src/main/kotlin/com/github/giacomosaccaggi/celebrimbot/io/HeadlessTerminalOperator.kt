package com.github.giacomosaccaggi.celebrimbot.io

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Standard ProcessBuilder implementation of TerminalOperator for standalone CLI usage.
 */
class HeadlessTerminalOperator(private val basePath: String) : TerminalOperator {

    override fun executeCommand(command: String): CompletableFuture<TerminalResult> {
        val future = CompletableFuture<TerminalResult>()

        Thread {
            try {
                val processBuilder = ProcessBuilder("/bin/sh", "-c", command)
                    .directory(java.io.File(basePath))
                    .redirectErrorStream(true)

                val process = processBuilder.start()
                val output = StringBuilder()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                reader.use {
                    var line: String? = it.readLine()
                    while (line != null) {
                        output.append(line).append("\n")
                        line = it.readLine()
                    }
                }

                val finished = process.waitFor(30, TimeUnit.SECONDS)
                val exitCode = if (finished) process.exitValue() else {
                    process.destroy()
                    output.append("\n[TIMEOUT WARNING: Process killed after 30 seconds]")
                    -1
                }

                future.complete(TerminalResult(output.toString(), exitCode))
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }.start()

        return future
    }
}
