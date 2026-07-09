package com.github.giacomosaccaggi.celebrimbot.http

import com.github.giacomosaccaggi.celebrimbot.index.PalantirIndex
import com.github.giacomosaccaggi.celebrimbot.io.*
import com.github.giacomosaccaggi.celebrimbot.mcp.McpRouter
import com.github.giacomosaccaggi.celebrimbot.registry.ToolRegistry
import com.github.giacomosaccaggi.celebrimbot.registry.tools.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.github.giacomosaccaggi.celebrimbot.ollama.ModelRouter
import com.github.giacomosaccaggi.celebrimbot.ollama.installOllamaRoutes
import java.io.File

/**
 * Standalone HTTP server for Celebrimbot.
 * Exposes REST API for team use + MCP JSON-RPC bridge.
 */
object CelebrimbotServer {

    private val gson = Gson()

    /** Singleton engine — shared across all requests to avoid loading the model multiple times. */
    private val engine: StandaloneLlmEngine by lazy {
        StandaloneLlmEngine(
            modelDir = File(System.getProperty("user.home"), ".celebrimbot/models"),
            unloadAfterSeconds = System.getenv("CELEBRIMBOT_MODEL_UNLOAD_TIMEOUT")?.toLongOrNull() ?: 300,
            logger = { msg -> println("[Celebrimbot] $msg") }
        )
    }

    /** ModelRouter — maps Ollama-style model names to GGUF files. Single model in RAM. */
    private val modelRouter: ModelRouter by lazy {
        ModelRouter(
            modelDir = File(System.getProperty("user.home"), ".celebrimbot/models"),
            unloadAfterSeconds = System.getenv("CELEBRIMBOT_MODEL_UNLOAD_TIMEOUT")?.toLongOrNull() ?: 300,
            logger = { msg -> println("[Celebrimbot] $msg") }
        )
    }

    fun start(port: Int, defaultWorkspace: String = "/workspace") {
        embeddedServer(Netty, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) { gson() }
            routing {
                get("/health") {
                    call.respond(mapOf("status" to "ok", "service" to "celebrimbot"))
                }

                post("/api/forge") {
                    val body = call.receiveText()
                    val json = try {
                        JsonParser.parseString(body).asJsonObject
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("status" to "error", "message" to "Invalid JSON"))
                        return@post
                    }

                    val prompt = json.get("prompt")?.asString
                    if (prompt.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("status" to "error", "message" to "Missing 'prompt' field"))
                        return@post
                    }

                    val workspacePath = json.get("workspace_path")?.asString ?: defaultWorkspace

                    val result = executeForge(prompt, workspacePath)
                    call.respond(result)
                }

                // Ollama-compatible API routes (Open WebUI, ProjectCompass, llama-index)
                installOllamaRoutes(modelRouter)

                post("/mcp") {
                    val body = call.receiveText()
                    val workspacePath = call.request.headers["X-Workspace-Path"] ?: defaultWorkspace
                    val fileOp = HeadlessFileOperator(workspacePath)
                    val scanOp = HeadlessProjectScanOperator(workspacePath)
                    val registry = buildToolRegistry(workspacePath)
                    val mcpRouter = McpRouter(registry, fileOp, scanOp)
                    val response = mcpRouter.handleRequest(body)
                    call.respondText(response, ContentType.Application.Json)
                }
            }
        }.start(wait = true)
    }

    private fun executeForge(prompt: String, workspacePath: String): Map<String, Any> {
        return try {
            if (!engine.isModelDownloaded()) {
                return mapOf(
                    "status" to "error",
                    "message" to "Model not downloaded. Run 'celebrimbot download-model' or start the container with model-loader."
                )
            }

            val response = engine.askQuestion(prompt, emptyList())
            mapOf(
                "status" to "ok",
                "result" to response,
                "workspace" to workspacePath
            )
        } catch (e: Exception) {
            mapOf("status" to "error", "message" to (e.message ?: "Unknown error"))
        }
    }

    private fun buildToolRegistry(workspacePath: String): ToolRegistry {
        val fileOp = HeadlessFileOperator(workspacePath)
        val termOp = HeadlessTerminalOperator(workspacePath)
        val webOp = DuckDuckGoSearchOperator()
        val scanOp = HeadlessProjectScanOperator(workspacePath)
        val gitOp = HeadlessGitOperator(workspacePath)

        return ToolRegistry().apply {
            register(ReadFileTool(fileOp))
            register(WriteFileTool(fileOp))
            register(DeleteFileTool(fileOp))
            register(RunTerminalTool(termOp, 60))
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
    }
}
