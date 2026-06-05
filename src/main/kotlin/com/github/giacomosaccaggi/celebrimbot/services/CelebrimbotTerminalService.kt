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

    private var currentCliProcess: Process? = null

    /**
     * Runs a CLI command and returns its meaningful stdout as a string (blocking).
     * Filters noise and returns once the response is detected or process ends.
     */
    fun runCliAndWait(command: String, timeoutSeconds: Long = 120): String {
        currentCliProcess?.destroyForcibly()
        currentCliProcess = null
        try {
            val process = ProcessBuilder("/bin/sh", "-c", command)
                .directory(java.io.File(project.basePath ?: "."))
                .redirectErrorStream(true)
                .start()
            currentCliProcess = process
            val result = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutSeconds * 1000

            process.inputStream.bufferedReader().use { reader ->
                while (System.currentTimeMillis() < deadline) {
                    val line = reader.readLine() ?: break
                    // Strip ANSI
                    val clean = line.replace(Regex("\u001B\\[[^A-Za-z]*[A-Za-z]"), "")
                        .replace(Regex("\u001B\\[\\?[0-9]+[hl]"), "")
                        .replace("\u0007", "")
                        .trim()
                    if (clean.isEmpty()) continue
                    // Skip noise
                    if (clean.contains("Thinking...") && !clean.contains("> ")) continue
                    if (clean.contains("mcp servers initialized")) continue
                    if (clean.contains("Mcp error:")) continue
                    if (clean.contains("KIRO_LOG_LEVEL")) continue
                    if (clean.startsWith("Picking up")) continue
                    if (clean.startsWith("Model:")) continue
                    if (clean.startsWith("All tools are now trusted")) continue
                    if (clean.startsWith("Agents can sometimes")) continue
                    if (clean.startsWith("Learn more at")) continue
                    if (clean.startsWith("Authenticated")) continue
                    if (clean.startsWith("Error: Stream closed")) continue
                    if (clean.contains("ctrl-c")) continue
                    if (clean.contains("loaded in ")) continue
                    if (clean.startsWith("▸ Time:")) { break } // End of response
                    if (clean.all { c -> c.code > 0x2800 || c.isWhitespace() || c == '7' || c == '8' }) continue
                    if (clean.length > 20 && clean.count { c -> c.code > 0x2500 } > clean.length / 2) continue
                    if (clean.startsWith("╭") || clean.startsWith("╰") || (clean.startsWith("│") && clean.endsWith("│"))) continue
                    if (clean.startsWith("Did you know?")) continue
                    // Extract kiro response from Thinking line
                    val meaningful = if (clean.contains("> ")) clean.substringAfterLast("> ").trim()
                        else clean.replace(Regex("^\\+\\s*\\d+:"), "").trim()
                    if (meaningful.isNotEmpty()) result.appendLine(meaningful)
                }
            }
            process.destroyForcibly()
            // Post-process: remove residual noise patterns mixed into response lines
            val cleaned = result.toString()
                .replace(Regex("7?[⠀-⣿]+8?7?"), "")  // braille spinners
                .replace(Regex("✗ \\S+ has failed to load after [\\d.]+ s"), "")
                .replace(Regex("✓ \\S+ loaded in [\\d.]+ s"), "")
                .lines()
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .trim()
            return cleaned
        } catch (e: Exception) {
            return "Error: ${e.message}"
        } finally {
            currentCliProcess = null
        }
    }

    /**
     * Runs a CLI command and streams its stdout back via [onOutput].
     * Kills any previously running CLI process before starting.
     */
    fun runCliAndStream(
        command: String,
        onOutput: (String) -> Unit,
        onDone: () -> Unit
    ) {
        // Kill previous process if still running
        currentCliProcess?.destroyForcibly()
        currentCliProcess = null

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val process = ProcessBuilder("/bin/sh", "-c", command)
                    .directory(java.io.File(project.basePath ?: "."))
                    .redirectErrorStream(true)
                    .start()
                currentCliProcess = process

                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        onOutput(line)
                        line = reader.readLine()
                    }
                }
                process.waitFor(120, TimeUnit.SECONDS)
            } catch (e: Exception) {
                onOutput("Error: ${e.message}")
            } finally {
                currentCliProcess = null
                onDone()
            }
        }
    }

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
