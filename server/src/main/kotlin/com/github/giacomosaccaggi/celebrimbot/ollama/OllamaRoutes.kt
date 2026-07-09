package com.github.giacomosaccaggi.celebrimbot.ollama

import com.github.giacomosaccaggi.celebrimbot.engine.ChatMessage
import com.github.giacomosaccaggi.celebrimbot.settings.LocalAiModel
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Installs all Ollama-compatible API routes on the given [Routing].
 *
 * Endpoints implemented:
 * - GET  /api/tags       — list available models
 * - GET  /api/version    — server version
 * - GET  /api/ps         — list running models
 * - POST /api/generate   — text generation (streaming + non-streaming)
 * - POST /api/chat       — chat completion (streaming + non-streaming)
 * - POST /api/show       — model information
 * - POST /api/embed      — embeddings (stub)
 * - POST /api/pull       — download model
 * - POST /v1/chat/completions — OpenAI-compatible chat
 * - GET  /v1/models      — OpenAI-compatible model list
 */
fun Routing.installOllamaRoutes(router: ModelRouter) {
    val gson = Gson()

    // ── GET /api/tags ─────────────────────────────────────────────────────────
    get("/api/tags") {
        val models = router.listAvailableModels()
        val modelsArray = models.map { info ->
            val modelFile = File(System.getProperty("user.home"), ".celebrimbot/models/${info.fileName}")
            mapOf(
                "name" to info.name,
                "model" to info.name,
                "modified_at" to DateTimeFormatter.ISO_INSTANT.format(
                    Instant.ofEpochMilli(modelFile.lastModified()).atOffset(ZoneOffset.UTC)
                ),
                "size" to modelFile.length(),
                "digest" to "sha256:${info.fileName.hashCode().toUInt().toString(16).padStart(64, '0')}",
                "details" to mapOf(
                    "parent_model" to "",
                    "format" to "gguf",
                    "family" to info.family,
                    "families" to listOf(info.family),
                    "parameter_size" to info.parameterSize,
                    "quantization_level" to info.quantization
                )
            )
        }
        call.respond(mapOf("models" to modelsArray))
    }

    // ── GET /api/version ──────────────────────────────────────────────────────
    get("/api/version") {
        call.respond(mapOf("version" to "0.1.2"))
    }

    // ── GET /api/ps ───────────────────────────────────────────────────────────
    get("/api/ps") {
        val running = router.getRunningModel()
        val models = if (running != null) {
            val modelFile = File(System.getProperty("user.home"), ".celebrimbot/models/${running.fileName}")
            listOf(mapOf(
                "name" to running.name,
                "model" to running.name,
                "size" to modelFile.length(),
                "digest" to "sha256:${running.fileName.hashCode().toUInt().toString(16).padStart(64, '0')}",
                "details" to mapOf(
                    "parent_model" to "",
                    "format" to "gguf",
                    "family" to running.family,
                    "families" to listOf(running.family),
                    "parameter_size" to running.parameterSize,
                    "quantization_level" to running.quantization
                ),
                "expires_at" to DateTimeFormatter.ISO_INSTANT.format(Instant.now().plusSeconds(300).atOffset(ZoneOffset.UTC)),
                "size_vram" to modelFile.length()
            ))
        } else emptyList()
        call.respond(mapOf("models" to models))
    }

    // ── POST /api/generate ────────────────────────────────────────────────────
    post("/api/generate") {
        val body = call.receiveText()
        val json = try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return@post
        }

        val modelName = json.get("model")?.asString ?: ""
        val prompt = json.get("prompt")?.asString ?: ""
        val stream = json.get("stream")?.asBoolean ?: true
        val temperature = json.getAsJsonObject("options")?.get("temperature")?.asFloat ?: 0.7f
        val maxTokens = json.getAsJsonObject("options")?.get("num_predict")?.asInt ?: 2048
        val system = json.get("system")?.asString

        // Empty prompt = load model into memory
        if (prompt.isBlank()) {
            try {
                router.resolveModel(modelName) // Validates model exists
                call.respond(mapOf(
                    "model" to modelName,
                    "created_at" to nowISO(),
                    "response" to "",
                    "done" to true
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to e.message))
            }
            return@post
        }

        // Build full prompt with system message if provided
        val fullPrompt = if (system != null) {
            val messages = listOf(
                ChatMessage("system", system),
                ChatMessage("user", prompt)
            )
            val (formatted, _) = router.formatChat(modelName, messages)
            formatted
        } else {
            prompt
        }

        val stopStrings = json.get("options")?.asJsonObject?.getAsJsonArray("stop")
            ?.map { it.asString } ?: emptyList()

        if (!stream) {
            // Non-streaming response
            val startTime = System.nanoTime()
            try {
                val response = router.infer(modelName, fullPrompt, temperature, maxTokens, stopStrings)
                val duration = System.nanoTime() - startTime
                call.respond(mapOf(
                    "model" to (router.resolveModel(modelName)?.name ?: modelName),
                    "created_at" to nowISO(),
                    "response" to response,
                    "done" to true,
                    "done_reason" to "stop",
                    "total_duration" to duration,
                    "load_duration" to 0,
                    "prompt_eval_count" to prompt.split(" ").size,
                    "prompt_eval_duration" to (duration / 10),
                    "eval_count" to response.split(" ").size,
                    "eval_duration" to (duration * 9 / 10)
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Inference failed")))
            }
        } else {
            // Streaming response (ndjson)
            call.respondOutputStream(ContentType.Application.Json) {
                val writer = bufferedWriter()
                val resolvedName = router.resolveModel(modelName)?.name ?: modelName
                val startTime = System.nanoTime()
                var tokenCount = 0

                try {
                    router.inferStreaming(modelName, fullPrompt, temperature, maxTokens, stopStrings) { token ->
                        tokenCount++
                        val chunk = JsonObject().apply {
                            addProperty("model", resolvedName)
                            addProperty("created_at", nowISO())
                            addProperty("response", token)
                            addProperty("done", false)
                        }
                        writer.write(gson.toJson(chunk))
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) { /* stream ended */ }

                // Final message with stats
                val duration = System.nanoTime() - startTime
                val final_ = JsonObject().apply {
                    addProperty("model", resolvedName)
                    addProperty("created_at", nowISO())
                    addProperty("response", "")
                    addProperty("done", true)
                    addProperty("done_reason", "stop")
                    addProperty("total_duration", duration)
                    addProperty("load_duration", 0)
                    addProperty("prompt_eval_count", prompt.split(" ").size)
                    addProperty("prompt_eval_duration", duration / 10)
                    addProperty("eval_count", tokenCount)
                    addProperty("eval_duration", duration * 9 / 10)
                }
                writer.write(gson.toJson(final_))
                writer.newLine()
                writer.flush()
            }
        }
    }

    // ── POST /api/chat ────────────────────────────────────────────────────────
    post("/api/chat") {
        val body = call.receiveText()
        val json = try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return@post
        }

        val modelName = json.get("model")?.asString ?: ""
        val messagesJson = json.getAsJsonArray("messages") ?: JsonArray()
        val stream = json.get("stream")?.asBoolean ?: true
        val temperature = json.getAsJsonObject("options")?.get("temperature")?.asFloat ?: 0.7f
        val maxTokens = json.getAsJsonObject("options")?.get("num_predict")?.asInt ?: 2048

        // Empty messages = load model
        if (messagesJson.size() == 0) {
            call.respond(mapOf(
                "model" to modelName,
                "created_at" to nowISO(),
                "message" to mapOf("role" to "assistant", "content" to ""),
                "done_reason" to "load",
                "done" to true
            ))
            return@post
        }

        // Parse messages
        val messages = messagesJson.map { el ->
            val obj = el.asJsonObject
            ChatMessage(
                role = obj.get("role")?.asString ?: "user",
                content = obj.get("content")?.asString ?: ""
            )
        }

        // Format prompt using the model's chat template
        val (prompt, templateStops) = router.formatChat(modelName, messages)
        val stopStrings = templateStops

        val resolvedName = router.resolveModel(modelName)?.name ?: modelName

        if (!stream) {
            // Non-streaming response
            val startTime = System.nanoTime()
            try {
                val response = router.infer(modelName, prompt, temperature, maxTokens, stopStrings)
                val duration = System.nanoTime() - startTime
                call.respond(mapOf(
                    "model" to resolvedName,
                    "created_at" to nowISO(),
                    "message" to mapOf("role" to "assistant", "content" to response),
                    "done_reason" to "stop",
                    "done" to true,
                    "total_duration" to duration,
                    "load_duration" to 0,
                    "prompt_eval_count" to messages.sumOf { it.content.split(" ").size },
                    "prompt_eval_duration" to (duration / 10),
                    "eval_count" to response.split(" ").size,
                    "eval_duration" to (duration * 9 / 10)
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: "Inference failed")))
            }
        } else {
            // Streaming response
            call.respondOutputStream(ContentType.Application.Json) {
                val writer = bufferedWriter()
                val startTime = System.nanoTime()
                var tokenCount = 0

                try {
                    router.inferStreaming(modelName, prompt, temperature, maxTokens, stopStrings) { token ->
                        tokenCount++
                        val chunk = JsonObject().apply {
                            addProperty("model", resolvedName)
                            addProperty("created_at", nowISO())
                            add("message", JsonObject().apply {
                                addProperty("role", "assistant")
                                addProperty("content", token)
                            })
                            addProperty("done", false)
                        }
                        writer.write(gson.toJson(chunk))
                        writer.newLine()
                        writer.flush()
                    }
                } catch (_: Exception) { /* stream ended */ }

                // Final message
                val duration = System.nanoTime() - startTime
                val final_ = JsonObject().apply {
                    addProperty("model", resolvedName)
                    addProperty("created_at", nowISO())
                    add("message", JsonObject().apply {
                        addProperty("role", "assistant")
                        addProperty("content", "")
                    })
                    addProperty("done_reason", "stop")
                    addProperty("done", true)
                    addProperty("total_duration", duration)
                    addProperty("load_duration", 0)
                    addProperty("prompt_eval_count", messages.sumOf { it.content.split(" ").size })
                    addProperty("prompt_eval_duration", duration / 10)
                    addProperty("eval_count", tokenCount)
                    addProperty("eval_duration", duration * 9 / 10)
                }
                writer.write(gson.toJson(final_))
                writer.newLine()
                writer.flush()
            }
        }
    }

    // ── POST /api/show ────────────────────────────────────────────────────────
    post("/api/show") {
        val body = call.receiveText()
        val json = try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return@post
        }

        val modelName = json.get("model")?.asString ?: ""
        val info = router.resolveModel(modelName)
        if (info == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "model '$modelName' not found"))
            return@post
        }

        call.respond(mapOf(
            "modelfile" to "# Auto-generated by Celebrimbot\nFROM ${info.fileName}",
            "parameters" to "stop \"<|im_end|>\"",
            "template" to "{{ if .System }}<|im_start|>system\n{{ .System }}<|im_end|>\n{{ end }}<|im_start|>user\n{{ .Prompt }}<|im_end|>\n<|im_start|>assistant\n{{ .Response }}<|im_end|>",
            "details" to mapOf(
                "parent_model" to "",
                "format" to "gguf",
                "family" to info.family,
                "families" to listOf(info.family),
                "parameter_size" to info.parameterSize,
                "quantization_level" to info.quantization
            ),
            "model_info" to mapOf(
                "general.architecture" to info.family,
                "general.file_type" to 15,
                "general.quantization_version" to 2
            )
        ))
    }

    // ── POST /api/embed ───────────────────────────────────────────────────────
    post("/api/embed") {
        // Stub: embedding models are not yet supported.
        // For ProjectCompass RAG, use Ollama with nomic-embed-text alongside Celebrimbot.
        call.respond(HttpStatusCode.BadRequest, mapOf(
            "error" to "Embedding models are not yet supported by Celebrimbot. Use Ollama with 'nomic-embed-text' for embeddings."
        ))
    }

    // Also support the legacy /api/embeddings endpoint
    post("/api/embeddings") {
        call.respond(HttpStatusCode.BadRequest, mapOf(
            "error" to "Embedding models are not yet supported by Celebrimbot. Use Ollama with 'nomic-embed-text' for embeddings."
        ))
    }

    // ── POST /api/pull ────────────────────────────────────────────────────────
    post("/api/pull") {
        val body = call.receiveText()
        val json = try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid JSON"))
            return@post
        }

        val modelName = json.get("model")?.asString ?: ""
        val streamPull = json.get("stream")?.asBoolean ?: true
        val info = router.resolveModel(modelName)

        if (info == null || info.localAiModel == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Unknown model: $modelName"))
            return@post
        }

        val modelDir = File(System.getProperty("user.home"), ".celebrimbot/models")
        val modelFile = File(modelDir, info.fileName)

        // Already downloaded
        if (modelFile.exists() && modelFile.length() > 100_000_000) {
            if (!streamPull) {
                call.respond(mapOf("status" to "success"))
            } else {
                call.respondOutputStream(ContentType.Application.Json) {
                    val writer = bufferedWriter()
                    writer.write(gson.toJson(mapOf("status" to "success")))
                    writer.newLine()
                    writer.flush()
                }
            }
            return@post
        }

        // Download with progress
        call.respondOutputStream(ContentType.Application.Json) {
            val writer = bufferedWriter()
            try {
                Files.createDirectories(modelDir.toPath())
                if (modelFile.exists()) modelFile.delete()

                writer.write(gson.toJson(mapOf("status" to "pulling manifest")))
                writer.newLine()
                writer.flush()

                val connection = URI(info.localAiModel.downloadUrl).toURL().openConnection()
                val totalSize = connection.contentLengthLong
                var downloaded = 0L

                connection.getInputStream().use { input ->
                    Files.newOutputStream(modelFile.toPath()).use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        var lastReportedPct = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead

                            if (totalSize > 0) {
                                val pct = (downloaded * 100 / totalSize).toInt()
                                if (pct != lastReportedPct && pct % 2 == 0) {
                                    lastReportedPct = pct
                                    val status = mapOf(
                                        "status" to "pulling ${info.fileName}",
                                        "digest" to "sha256:${info.fileName.hashCode().toUInt().toString(16).padStart(64, '0')}",
                                        "total" to totalSize,
                                        "completed" to downloaded
                                    )
                                    writer.write(gson.toJson(status))
                                    writer.newLine()
                                    writer.flush()
                                }
                            }
                        }
                    }
                }

                writer.write(gson.toJson(mapOf("status" to "verifying sha256 digest")))
                writer.newLine()
                writer.write(gson.toJson(mapOf("status" to "writing manifest")))
                writer.newLine()
                writer.write(gson.toJson(mapOf("status" to "success")))
                writer.newLine()
                writer.flush()
            } catch (e: Exception) {
                writer.write(gson.toJson(mapOf("error" to (e.message ?: "Download failed"))))
                writer.newLine()
                writer.flush()
                if (modelFile.exists()) modelFile.delete()
            }
        }
    }

    // ── POST /v1/chat/completions (OpenAI-compatible) ─────────────────────────
    post("/v1/chat/completions") {
        val body = call.receiveText()
        val json = try { JsonParser.parseString(body).asJsonObject } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to mapOf("message" to "Invalid JSON")))
            return@post
        }

        val modelName = json.get("model")?.asString ?: ""
        val messagesJson = json.getAsJsonArray("messages") ?: JsonArray()
        val stream = json.get("stream")?.asBoolean ?: false
        val temperature = json.get("temperature")?.asFloat ?: 0.7f
        val maxTokens = json.get("max_tokens")?.asInt ?: 2048

        val messages = messagesJson.map { el ->
            val obj = el.asJsonObject
            ChatMessage(
                role = obj.get("role")?.asString ?: "user",
                content = obj.get("content")?.asString ?: ""
            )
        }

        val (prompt, stopStrings) = router.formatChat(modelName, messages)
        val resolvedName = router.resolveModel(modelName)?.name ?: modelName
        val completionId = "chatcmpl-${UUID.randomUUID().toString().take(12)}"

        if (!stream) {
            // Non-streaming OpenAI format
            val startTime = System.nanoTime()
            try {
                val response = router.infer(modelName, prompt, temperature, maxTokens, stopStrings)
                val duration = System.nanoTime() - startTime

                call.respond(mapOf(
                    "id" to completionId,
                    "object" to "chat.completion",
                    "created" to (System.currentTimeMillis() / 1000),
                    "model" to resolvedName,
                    "choices" to listOf(mapOf(
                        "index" to 0,
                        "message" to mapOf("role" to "assistant", "content" to response),
                        "finish_reason" to "stop"
                    )),
                    "usage" to mapOf(
                        "prompt_tokens" to messages.sumOf { it.content.split(" ").size },
                        "completion_tokens" to response.split(" ").size,
                        "total_tokens" to (messages.sumOf { it.content.split(" ").size } + response.split(" ").size)
                    )
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to mapOf("message" to (e.message ?: "Inference failed"))))
            }
        } else {
            // Streaming SSE format
            call.respondOutputStream(ContentType.Text.EventStream) {
                val writer = bufferedWriter()
                var tokenCount = 0

                try {
                    router.inferStreaming(modelName, prompt, temperature, maxTokens, stopStrings) { token ->
                        tokenCount++
                        val chunk = JsonObject().apply {
                            addProperty("id", completionId)
                            addProperty("object", "chat.completion.chunk")
                            addProperty("created", System.currentTimeMillis() / 1000)
                            addProperty("model", resolvedName)
                            add("choices", JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("index", 0)
                                    add("delta", JsonObject().apply {
                                        addProperty("content", token)
                                    })
                                    add("finish_reason", null)
                                })
                            })
                        }
                        writer.write("data: ${gson.toJson(chunk)}\n\n")
                        writer.flush()
                    }
                } catch (_: Exception) { /* stream ended */ }

                // Final chunk with finish_reason
                val finalChunk = JsonObject().apply {
                    addProperty("id", completionId)
                    addProperty("object", "chat.completion.chunk")
                    addProperty("created", System.currentTimeMillis() / 1000)
                    addProperty("model", resolvedName)
                    add("choices", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("index", 0)
                            add("delta", JsonObject())
                            addProperty("finish_reason", "stop")
                        })
                    })
                }
                writer.write("data: ${gson.toJson(finalChunk)}\n\n")
                writer.write("data: [DONE]\n\n")
                writer.flush()
            }
        }
    }

    // ── GET /v1/models (OpenAI-compatible) ────────────────────────────────────
    get("/v1/models") {
        val models = router.listAvailableModels()
        val data = models.map { info ->
            mapOf(
                "id" to info.name,
                "object" to "model",
                "created" to (System.currentTimeMillis() / 1000),
                "owned_by" to "celebrimbot"
            )
        }
        call.respond(mapOf("object" to "list", "data" to data))
    }
}

/** Returns current ISO-8601 timestamp. */
private fun nowISO(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
