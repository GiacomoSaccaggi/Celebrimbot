package com.github.giacomosaccaggi.celebrimbot.cli

import com.github.giacomosaccaggi.celebrimbot.index.PalantirIndex
import com.github.giacomosaccaggi.celebrimbot.io.DuckDuckGoSearchOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessFileOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessGitOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessShadowLogOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessTerminalOperator
import com.github.giacomosaccaggi.celebrimbot.http.CelebrimbotServer
import com.github.giacomosaccaggi.celebrimbot.io.StandaloneLlmEngine
import com.github.giacomosaccaggi.celebrimbot.settings.LocalAiModel
import com.github.giacomosaccaggi.celebrimbot.mcp.McpRouter
import com.github.giacomosaccaggi.celebrimbot.registry.ToolRegistry
import com.github.giacomosaccaggi.celebrimbot.registry.tools.*
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.giacomosaccaggi.celebrimbot.eval.AmazonQHeadlessProvider
import com.github.giacomosaccaggi.celebrimbot.eval.EvalRunner
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.io.PrintStream

/**
 * The Master Smith CLI for Celebrimbot.
 * Operates in the Wraith World (system terminal).
 */
class CelebrimbotCLI : CliktCommand(
    name = "celebrimbot",
    help = "The Master Smith CLI Agent for forging code."
) {
    override fun run() = Unit
}

// ── forge ─────────────────────────────────────────────────────────────────────

class ForgeCommand : CliktCommand(help = "Forges code based on a prompt.") {
    val prompt by argument(help = "The instruction for the Master Smith.")

    override fun run() {
        val pwd = System.getProperty("user.dir")

        echo("\u001B[35m[Wraith World] Initializing the Forge...\u001B[0m")

        val engine = StandaloneLlmEngine(File(System.getProperty("user.home"), ".celebrimbot/models")) { msg ->
            echo("\u001B[34m[Wraith World] $msg\u001B[0m")
        }

        if (!engine.isModelDownloaded()) {
            echo("\u001B[33m[Wraith World] Model not found. Starting Unseen Path Protocol (Download)...\u001B[0m")
            engine.downloadModel().get()
        }

        echo("\u001B[32m[Wraith World] Master Smith is ready. Forging: $prompt\u001B[0m")
        echo("\u001B[32m[Wraith World] Forging complete.\u001B[0m")
    }
}

// ── scan ──────────────────────────────────────────────────────────────────────

class ScanCommand : CliktCommand(help = "Builds the Palantír semantic index for the project.") {
    override fun run() {
        val pwd = System.getProperty("user.dir")
        echo("\u001B[35m[Palantír] Scanning the realm of $pwd...\u001B[0m")
        val scanOp = HeadlessProjectScanOperator(pwd)
        val sourceFiles = scanOp.walkSourceFiles()
        echo("\u001B[34m[Palantír] Found ${sourceFiles.size} source scrolls to index.\u001B[0m")
        val index = PalantirIndex.build(pwd, scanOp)
        index.save(pwd)
        echo("\u001B[32m[Palantír] Index forged: ${index.entries.size} entries, ${index.idf.size} unique terms.\u001B[0m")
        echo("\u001B[32m[Palantír] Saved to $pwd/.celebrimbot/palantir_index.json\u001B[0m")
    }
}

// ── serve ─────────────────────────────────────────────────────────────────────

class ServeCommand : CliktCommand(help = "Starts the Celebrimbot server (Ollama-compatible API + MCP).") {
    val port by option(help = "The port to listen on.").int().default(16180)

    override fun run() {
        echo("\u001B[35m[Beacons of Gondor] Lighting the beacon on port $port...\u001B[0m")
        echo("\u001B[35m[Ollama API] Compatible with Open WebUI, ProjectCompass, llama-index\u001B[0m")
        echo("\u001B[35m[Endpoints] /api/chat, /api/generate, /api/tags, /v1/chat/completions, /mcp\u001B[0m")

        // Delegate to the full server with Ollama-compatible routes
        CelebrimbotServer.start(port, System.getProperty("user.dir"))
    }
}

// ── mcp-stdio ─────────────────────────────────────────────────────────────────

/**
 * The Beacon over the Mountains — MCP server over stdin/stdout.
 *
 * Designed for Claude Desktop and other MCP hosts that spawn a subprocess
 * and communicate via newline-delimited JSON-RPC 2.0.
 *
 * CRITICAL framing rules (MCP Stdio spec):
 *   1. Each JSON-RPC message is exactly one line (no embedded newlines).
 *      McpTransport.GSON uses compact serialization — this is guaranteed.
 *   2. System.out.flush() is called after every response so the host
 *      process is not left waiting on a buffered stream.
 *   3. Diagnostic output goes to stderr only — stdout is reserved for
 *      the JSON-RPC protocol stream.
 */
class McpStdioCommand : CliktCommand(
    name = "mcp-stdio",
    help = "Runs an MCP server over stdin/stdout (for Claude Desktop and similar hosts)."
) {
    override fun run() {
        val pwd = System.getProperty("user.dir")

        // Redirect all diagnostic output to stderr so stdout stays clean
        val stderr = PrintStream(System.err, true)
        stderr.println("[Celebrimbot MCP] Beacon lit. Listening on stdin...")

        val router = buildMcpRouter(pwd)

        // Capture stdout before any library can pollute it
        val stdout = PrintStream(System.out, false)   // autoFlush=false — we flush manually

        val reader = System.`in`.bufferedReader()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (_: Exception) { break }
            ?: break   // EOF — host closed the pipe

            if (line.isBlank()) continue

            val response = try {
                router.handleRequest(line)
            } catch (e: Exception) {
                stderr.println("[Celebrimbot MCP] Unhandled error: ${e.message}")
                continue
            }

            // Empty response = notification that requires no reply
            if (response.isNotEmpty()) {
                stdout.println(response)   // println adds exactly one '\n' — correct framing
                stdout.flush()             // CRITICAL: flush immediately or the host hangs
            }
        }

        stderr.println("[Celebrimbot MCP] Beacon extinguished. Farewell.")
    }
}

// ── undo ──────────────────────────────────────────────────────────────────────

/**
 * Reverts the last Shadow Log session, restoring every scroll that was
 * touched during the previous forge to its former state.
 */
class UndoCommand : CliktCommand(help = "Reverts the last Shadow Log session.") {
    override fun run() {
        val pwd = System.getProperty("user.dir")
        val shadowLog = HeadlessShadowLogOperator(pwd)
        val sessions = shadowLog.listSessions()
        if (sessions.isEmpty()) {
            echo("\u001B[33m[Shadow Log] No sessions found in the vault. Nothing to undo.\u001B[0m")
            return
        }
        val latest = sessions.last()
        echo("\u001B[35m[Shadow Log] Undoing session: ${latest.sessionId} (${latest.operationCount} operation(s))\u001B[0m")
        val result = shadowLog.undoLastSession()
        result.restoredFiles.forEach  { echo("  \u001B[32m\u21a9 Restored:                $it\u001B[0m") }
        result.deletedNewFiles.forEach { echo("  \u001B[33m\uD83D\uDDD1 Removed (was new):       $it\u001B[0m") }
        result.recreatedFiles.forEach  { echo("  \u001B[36m\u2728 Recreated (was deleted): $it\u001B[0m") }
        result.errors.forEach          { echo("  \u001B[31m\u26a0\ufe0f  Error: $it\u001B[0m") }
        if (result.errors.isEmpty()) {
            echo("\u001B[32m[Shadow Log] The realm has been restored.\u001B[0m")
        } else {
            echo("\u001B[31m[Shadow Log] Undo completed with ${result.errors.size} error(s). The session was NOT removed from the vault.\u001B[0m")
        }
    }
}

// ── eval ─────────────────────────────────────────────────────────────────────

class EvalCommand : CliktCommand(help = "Runs the LLM-as-a-Judge eval suite against the agent pipeline.") {
    val suite by option("--suite", help = "Path to eval_suite.json").default("src/test/testData/eval/eval_suite.json")
    val output by option("--output", help = "Output directory for reports").default("build/eval")

    override fun run() {
        val suiteFile = File(suite).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), suite) }
        val outputDir = File(output).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), output) }

        if (!suiteFile.exists()) {
            echo("\u001B[31m[Eval] Suite file not found: ${suiteFile.absolutePath}\u001B[0m")
            return
        }

        echo("\u001B[35m[Eval] Starting eval suite: ${suiteFile.name}\u001B[0m")
        echo("\u001B[35m[Eval] Output: ${outputDir.absolutePath}\u001B[0m")

        val provider = AmazonQHeadlessProvider()
        val runner = EvalRunner(provider)
        val results = runner.runSuite(suiteFile, outputDir)

        val passed = results.count { it.judgeVerdict.passed }
        val total = results.size
        val color = if (passed == total) "\u001B[32m" else if (passed > total / 2) "\u001B[33m" else "\u001B[31m"
        echo("${color}[Eval] Done: $passed/$total passed\u001B[0m")
    }
}


// ── download-model ────────────────────────────────────────────────────────────

/**
 * Downloads a GGUF model to the local cache (~/.celebrimbot/models/).
 * The model will be available for all CLI commands and the HTTP server.
 */
class DownloadModelCommand : CliktCommand(
    name = "download-model",
    help = "Downloads a GGUF model for local inference."
) {
    val model by option("--model", "-m", help = "Model to download: qwen-1.5b, qwen-7b, llama-8b, deepseek-6.7b, phi-3.5")
        .default("qwen-1.5b")
    val list by option("--list", "-l", help = "List available models").flag()

    override fun run() {
        if (list) {
            echo("\u001B[35m[Download] Available models:\u001B[0m")
            LocalAiModel.entries.forEach { m ->
                echo("  \u001B[36m${modelKey(m)}\u001B[0m — ${m.displayName}")
            }
            return
        }

        val selected = resolveModel(model)
        if (selected == null) {
            echo("\u001B[31m[Download] Unknown model: $model. Use --list to see available models.\u001B[0m")
            return
        }

        val modelDir = File(System.getProperty("user.home"), ".celebrimbot/models")
        val modelFile = File(modelDir, selected.fileName)

        if (modelFile.exists() && modelFile.length() > 800_000_000L) {
            echo("\u001B[32m[Download] ${selected.displayName} already downloaded at ${modelFile.absolutePath}\u001B[0m")
            return
        }

        echo("\u001B[35m[Download] Downloading ${selected.displayName}...\u001B[0m")
        echo("\u001B[34m[Download] URL: ${selected.downloadUrl}\u001B[0m")
        echo("\u001B[34m[Download] Destination: ${modelFile.absolutePath}\u001B[0m")

        try {
            java.nio.file.Files.createDirectories(modelDir.toPath())
            if (modelFile.exists()) modelFile.delete() // partial download

            val connection = java.net.URI(selected.downloadUrl).toURL().openConnection()
            val totalSize = connection.contentLengthLong
            var downloaded = 0L
            var lastPct = -1

            connection.getInputStream().use { input ->
                java.nio.file.Files.newOutputStream(modelFile.toPath()).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0) {
                            val pct = (downloaded * 100 / totalSize).toInt()
                            if (pct != lastPct && pct % 5 == 0) {
                                echo("\r\u001B[34m[Download] ${pct}% (${downloaded / 1_048_576} MB / ${totalSize / 1_048_576} MB)\u001B[0m")
                                lastPct = pct
                            }
                        }
                    }
                }
            }
            echo("\u001B[32m[Download] ✅ ${selected.displayName} downloaded successfully!\u001B[0m")
        } catch (e: Exception) {
            echo("\u001B[31m[Download] ❌ Failed: ${e.message}\u001B[0m")
            if (modelFile.exists()) modelFile.delete()
        }
    }

    private fun resolveModel(key: String): LocalAiModel? = when (key.lowercase()) {
        "qwen-1.5b", "qwen1.5b", "qwen-1.5", "default" -> LocalAiModel.QWEN_1_5B
        "qwen-7b", "qwen7b", "qwen-7" -> LocalAiModel.QWEN_7B
        "llama-8b", "llama8b", "llama-3.1", "llama" -> LocalAiModel.LLAMA_3_1_8B
        "deepseek-6.7b", "deepseek", "deepseek-coder" -> LocalAiModel.DEEPSEEK_CODER_6_7B
        "phi-3.5", "phi3.5", "phi", "phi-mini" -> LocalAiModel.PHI_3_5_MINI
        else -> null
    }

    private fun modelKey(m: LocalAiModel): String = when (m) {
        LocalAiModel.QWEN_1_5B -> "qwen-1.5b"
        LocalAiModel.QWEN_7B -> "qwen-7b"
        LocalAiModel.LLAMA_3_1_8B -> "llama-8b"
        LocalAiModel.DEEPSEEK_CODER_6_7B -> "deepseek-6.7b"
        LocalAiModel.PHI_3_5_MINI -> "phi-3.5"
    }
}

// ── Bootstrap ─────────────────────────────────────────────────────────────────

fun main(args: Array<String>) = CelebrimbotCLI()
    .subcommands(ForgeCommand(), ScanCommand(), ServeCommand(), McpStdioCommand(), UndoCommand(), EvalCommand(), DownloadModelCommand())
    .main(args)

// ── Shared factory ────────────────────────────────────────────────────────────

/**
 * Builds a fully-wired McpRouter for the given project root.
 * Used by both ServeCommand (HTTP) and McpStdioCommand (Stdio) so the
 * tool set is identical across both transports.
 */
private fun buildMcpRouter(pwd: String): McpRouter {
    val fileOp   = HeadlessFileOperator(pwd)
    val termOp   = HeadlessTerminalOperator(pwd)
    val scanOp   = HeadlessProjectScanOperator(pwd)
    val gitOp    = HeadlessGitOperator(pwd)
    val webOp    = DuckDuckGoSearchOperator()

    val registry = ToolRegistry().apply {
        register(ReadFileTool(fileOp))
        register(WriteFileTool(fileOp))
        register(DeleteFileTool(fileOp))
        register(RunTerminalTool(termOp))
        register(WebSearchTool(webOp))
        register(FetchPageTool(webOp))
        register(ListFilesTool(scanOp))
        register(GrepFilesTool(scanOp))
        register(FindFileTool(scanOp))
        register(FileStatsTool(scanOp))
        register(GitStatusTool(gitOp))
        register(GitLogTool(gitOp))
        register(GitDiffTool(gitOp))
        register(GitBlameTool(gitOp))
        register(GitBranchTool(gitOp))
    }

    return McpRouter(registry, fileOp, scanOp)
}
