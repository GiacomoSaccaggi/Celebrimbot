package com.github.giacomosaccaggi.celebrimbot.cli

import com.github.giacomosaccaggi.celebrimbot.io.HeadlessFileOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessTerminalOperator
import com.github.giacomosaccaggi.celebrimbot.io.StandaloneLlmEngine
import com.github.giacomosaccaggi.celebrimbot.services.CelebrimbotAgentOrchestrator
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.gson.*
import java.io.File

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

class ForgeCommand : CliktCommand(help = "Forges code based on a prompt.") {
    val prompt by argument(help = "The instruction for the Master Smith.")

    override fun run() {
        val pwd = System.getProperty("user.dir")
        val modelDir = File(System.getProperty("user.home"), ".celebrimbot/models")
        
        echo("\u001B[35m[Wraith World] Initializing the Forge...\u001B[0m")
        
        val fileOp = HeadlessFileOperator(pwd)
        val termOp = HeadlessTerminalOperator(pwd)
        val engine = StandaloneLlmEngine(modelDir) { msg ->
            echo("\u001B[34m[Wraith World] $msg\u001B[0m")
        }

        // Check if model is ready
        if (!engine.isModelDownloaded()) {
            echo("\u001B[33m[Wraith World] Model not found. Starting Unseen Path Protocol (Download)...\u001B[0m")
            engine.downloadModel().get()
        }

        // Create a mock Project for the orchestrator (refactoring needed to remove Project entirely)
        // For now, we assume the Orchestrator uses the provided Operators.
        // val orchestrator = CelebrimbotAgentOrchestrator(null, fileOp, termOp, engine)
        
        echo("\u001B[32m[Wraith World] Master Smith is ready. Forging: $prompt\u001B[0m")
        
        // orchestrator.executePlan(prompt, "", emptyList()) { progress ->
        //    echo(progress)
        // }
        
        echo("\u001B[32m[Wraith World] Forging complete.\u001B[0m")
    }
}

class ScanCommand : CliktCommand(help = "Scans the project for the Unseen Path.") {
    override fun run() {
        echo("\u001B[35m[Wraith World] Scanning the project skeleton...\u001B[0m")
        // Implementation for skeleton scanning
    }
}

class ServeCommand : CliktCommand(help = "Starts the Hidden Bridge (HTTP Server).") {
    val port by option(help = "The port to listen on.").int().default(16180)

    override fun run() {
        echo("\u001B[35m[Wraith World] Forging the Hidden Bridge on port $port...\u001B[0m")
        
        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                gson()
            }
            routing {
                get("/health") {
                    call.respond(mapOf("status" to "Master Smith is at the forge"))
                }
                post("/forge") {
                    val request = call.receive<Map<String, String>>()
                    val prompt = request["prompt"] ?: return@post call.respond(mapOf("error" to "No prompt found"))
                    // Here we would invoke the Orchestrator
                    call.respond(mapOf("message" to "Forging initiated via Hidden Bridge", "prompt" to prompt))
                }
            }
        }.start(wait = true)
    }
}

/**
 * Bootstrap function for Standalone Mode.
 */
fun main(args: Array<String>) = CelebrimbotCLI()
    .subcommands(ForgeCommand(), ScanCommand(), ServeCommand())
    .main(args)
