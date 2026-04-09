package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.settings.AiProvider
import com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotPasswordSafe
import com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Service(Service.Level.PROJECT)
class CelebrimbotLlmService(private val project: Project) {

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val gson = Gson()

    private val chatPersona = """
        You are Celebrimbot, a helpful AI assistant integrated into a JetBrains IDE. Answer conversationally and concisely. Do NOT output any code blocks or terminal commands unless explicitly asked.
    """.trimIndent()

    private val plannerPersona = """
        You are the Planner in a Multi-Agent system. Given a user request, decide the best strategy.
        
        Respond with a JSON object with a "strategy" field:
        
        1. If you can answer directly (questions, explanations, no file edits needed):
           {"strategy":"direct","response":"your answer here"}
        
        2. If the task requires file edits or terminal commands, create a plan:
           {"strategy":"plan","tasks":[{"id":1,"action":"read_psi","target":"Main.java"},{"id":2,"action":"write_code","target":"Main.java","instruction":"remove the second Main class"}]}
        
        Available actions: "read_psi" (read file), "write_code" (edit file), "run_terminal" (run command).
        IMPORTANT: Always include "read_psi" before "write_code" on existing files.
        Output ONLY the JSON object, no explanation.
    """.trimIndent()

    private val workerPersona = """
        You are the Worker in a Multi-Agent system. Execute a SINGLE atomic task.
        If the task is "write_code" and you receive the current file content, you MUST output the COMPLETE modified file — not just the changed part, not an empty class. Preserve all existing code except what the instruction asks to change.
        Output ONLY the code in a markdown code block.
        If the task is "run_terminal", output ONLY: ${"$$"}RUN_COMMAND: command${"$$"}.
    """.trimIndent()

    private val systemPersona = """
        You are Celebrimbot, an Autonomous AI Coding Agent integrated into a JetBrains IDE. You receive the Project Skeleton and the user's currently highlighted code in the active editor. YOU HAVE TERMINAL EXECUTION POWERS. If the user asks you to test, compile, or run code, you MUST output a terminal command using this exact syntax: ${"$$"}RUN_COMMAND: your_bash_command_here${"$$"}. For example, ${"$$"}RUN_COMMAND: javac src/Main.java && java -cp src Main${"$$"}. When the user asks you to fix and test code, you must do 2 things in your response: 1. Provide the corrected code in markdown blocks (so the IDE can auto-apply it to the active editor). 2. Immediately output the ${"$$"}RUN_COMMAND: ...${"$$"} tag to test what you just wrote. If the execution fails, the system will automatically feed the error logs back to you, and you must fix the code and run it again.
    """.trimIndent()

    fun askChat(prompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$chatPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return "[🖥️ Local Qwen] " + embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, chatPersona)
            if (!result.startsWith("Error:")) return "[☁️ Alibaba Qwen] $result"
        }
        return fallbackToEmbedded("<|im_start|>system\n$chatPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
    }

    fun askPlanner(prompt: String): String {
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, plannerPersona)
            if (!result.startsWith("Error:")) return result
        }
        val geminiResult = callExternalLlm(prompt, plannerPersona, forcedGemini = true)
        if (!geminiResult.startsWith("Error:")) return geminiResult
        // Fallback: embedded with JSON priming
        val simplifiedPrompt = "<|im_start|>system\n$plannerPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{"
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            return "{" + embeddedEngine.askQuestion(simplifiedPrompt, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        return fallbackToEmbedded(simplifiedPrompt)
    }

    fun askWorker(prompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$workerPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, workerPersona)
            if (!result.startsWith("Error:")) return result
        }
        val result = callExternalLlm(prompt, workerPersona)
        if (!result.startsWith("Error:")) return result
        return fallbackToEmbedded("<|im_start|>system\n$workerPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
    }

    private fun fallbackToEmbedded(fullPrompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (!embeddedEngine.isModelDownloaded()) {
            embeddedEngine.downloadModel()
            return "<i>[System: External API failed. Downloading local model for fallback — retry in a moment.]</i>"
        }
        // Wrap in Qwen chat template if not already formatted
        val prompt = if (fullPrompt.contains("<|im_start|>")) fullPrompt
            else "<|im_start|>user\n$fullPrompt<|im_end|>\n<|im_start|>assistant\n"
        return embeddedEngine.askQuestion(prompt, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
    }

    fun askQuestion(prompt: String): String {
        return askWorker(prompt) // Keep backward compatibility or refactor later
    }

    private fun callAlibabaResponses(prompt: String, persona: String, tools: List<String> = emptyList()): String {
        val apiKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: return "Error: No Alibaba API key"
        val settings = CelebrimbotSettingsState.getInstance(project).state
        val model = if (settings.provider == AiProvider.ALIBABA_QWEN) settings.modelName else "qwen-plus"

        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "input" to mapOf(
                "messages" to listOf(
                    mapOf("role" to "system", "content" to persona),
                    mapOf("role" to "user", "content" to prompt)
                )
            )
        )
        // Only attach tools that have a cost justification (web_search for planner, code_interpreter is free)
        if (tools.isNotEmpty()) {
            payload["tools"] = tools.map { mapOf("type" to it) }
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/text-generation/generation"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root = gson.fromJson(response.body(), com.google.gson.JsonObject::class.java)
                root.getAsJsonObject("output")
                    ?.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                    ?: "Error: Unexpected Alibaba response format"
            } else {
                "Error: HTTP ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun callExternalLlm(prompt: String, persona: String, forcedGemini: Boolean = false): String {
        val settings = CelebrimbotSettingsState.getInstance(project).state
        val apiKey = CelebrimbotPasswordSafe.getApiKey(project) ?: ""
        val baseUrl = if (forcedGemini) "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent" else settings.baseUrl
        val modelName = if (forcedGemini) "gemini-1.5-flash" else settings.modelName
        val provider = if (forcedGemini) AiProvider.GOOGLE_GEMINI else settings.provider

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))

        val payload = when (provider) {
            AiProvider.GOOGLE_GEMINI -> {
                requestBuilder.header("x-goog-api-key", apiKey)
                mapOf(
                    "systemInstruction" to mapOf(
                        "parts" to listOf(mapOf("text" to persona))
                    ),
                    "contents" to listOf(
                        mapOf("parts" to listOf(mapOf("text" to prompt)))
                    )
                )
            }
            AiProvider.LOCAL_API, AiProvider.ALIBABA_QWEN -> {
                if (apiKey.isNotEmpty()) requestBuilder.header("Authorization", "Bearer $apiKey")
                mapOf(
                    "model" to modelName,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to persona),
                        mapOf("role" to "user", "content" to prompt)
                    )
                )
            }
        }

        val jsonPayload = gson.toJson(payload)
        val request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                parseResponse(response.body(), provider)
            } else {
                "Error: HTTP ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun parseResponse(json: String, provider: AiProvider): String {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            when (provider) {
                AiProvider.LOCAL_API, AiProvider.ALIBABA_QWEN -> {
                    // OpenAI format: choices[0].message.content
                    root.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString
                        ?: "Unexpected response format (LOCAL_API)"
                }
                AiProvider.GOOGLE_GEMINI -> {
                    // Gemini format: candidates[0].content.parts[0].text
                    root.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString
                        ?: "Unexpected response format (GOOGLE_GEMINI)"
                }
            }
        } catch (e: Exception) {
            "Error parsing response: ${e.message}"
        }
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotLlmService = project.service()
    }

    fun buildProjectSkeleton(project: Project): String {
        val skeleton = StringBuilder("Project Structure:\n")
        val fileIndex = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)

        fileIndex.iterateContent { virtualFile ->
            if (!virtualFile.isDirectory && !virtualFile.name.startsWith(".")) {
                if (fileIndex.isInSourceContent(virtualFile)) {
                    val relativePath = virtualFile.path.removePrefix(project.basePath ?: "")
                    skeleton.append("- $relativePath\n")

                    val psiFile = psiManager.findFile(virtualFile)
                    psiFile?.children?.forEach { element ->
                        if (element is PsiNamedElement) {
                            skeleton.append("  - ${element.name}\n")
                        }
                    }
                }
            }
            true
        }
        return skeleton.toString()
    }
}
