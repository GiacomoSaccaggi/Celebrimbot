package com.github.giacomosaccaggi.celebrimbot.io

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Standard ProcessBuilder implementation of TerminalOperator for standalone CLI usage.
 */
class HeadlessTerminalOperator(private val basePath: String) : TerminalOperator {

    override fun executeCommand(command: String): CompletableFuture<String> {
        val future = CompletableFuture<String>()
        
        Thread {
            try {
                val processBuilder = ProcessBuilder("/bin/sh", "-c", command)
                    .directory(java.io.File(basePath))
                    .redirectErrorStream(true)
                
                val process = processBuilder.start()
                val output = StringBuilder()
                
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                try {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        output.append(line).append("\n")
                        line = reader.readLine()
                    }
                } finally {
                    reader.close()
                }
                
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroy()
                    output.append("\n[TIMEOUT WARNING: Process killed after 30 seconds]")
                }
                
                future.complete(output.toString())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }.start()
        
        return future
    }
}
