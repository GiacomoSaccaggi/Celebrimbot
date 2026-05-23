package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotPasswordSafe
import com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
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

    private val logger = Logger.getInstance(CelebrimbotLlmService::class.java)

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()
    
    private val gson = Gson()

    private val galadrielPersona = loadPrompt("galadriel_system_prompt.txt")
    private val elrondPersona = loadPrompt("elrond_system_prompt.txt")
    private val samwisePersona = loadPrompt("samwise_system_prompt.txt")
    private val celebrimborPersona = loadPrompt("celebrimbor_system_prompt.txt")

    fun askChat(prompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$galadrielPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return "[🧝 Galadriel] " + embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, galadrielPersona)
            if (!result.startsWith("Error:")) return "[🧝 Galadriel] $result"
        }
        return fallbackToEmbedded("<|im_start|>system\n$galadrielPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
    }

    fun askAragorn(prompt: String, toolSchema: String? = null): String {
        val rawPersona = loadPrompt("aragorn_system_prompt.txt")
        val aragornPersona = if (toolSchema != null) rawPersona.replace("{{TOOLS}}", toolSchema) else rawPersona
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$aragornPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{"
            return "{" + embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        return fallbackToEmbedded("<|im_start|>system\n$aragornPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{")
    }

        fun askElrond(prompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$elrondPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{"
            return "{" + embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        return fallbackToEmbedded("<|im_start|>system\n$elrondPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{")
    }

    fun askCelebrimbor(prompt: String, toolSchema: String? = null): String {
        val persona = if (toolSchema != null) celebrimborPersona.replace("{{TOOLS}}", toolSchema) else celebrimborPersona
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, persona)
            if (!result.startsWith("Error:")) return result
        }
        val geminiResult = callExternalLlm(prompt, persona, forcedGemini = true)
        if (!geminiResult.startsWith("Error:")) return geminiResult
        val simplifiedPrompt = "<|im_start|>system\n$persona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{"
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            return "{" + embeddedEngine.askQuestion(simplifiedPrompt, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        return fallbackToEmbedded(simplifiedPrompt)
    }

    fun askBilbo(conversationLog: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        val bilboPersona = loadPrompt("bilbo_system_prompt.txt")
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$bilboPersona<|im_end|>\n<|im_start|>user\n$conversationLog<|im_end|>\n<|im_start|>assistant\n"
            return embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        return fallbackToEmbedded("<|im_start|>system\n$bilboPersona<|im_end|>\n<|im_start|>user\n$conversationLog<|im_end|>\n<|im_start|>assistant\n")
    }

    private val frodoPersona = loadPrompt("frodo_system_prompt.txt")
    private val legolasGimliPersona = loadPrompt("legolas_gimli_system_prompt.txt")

    fun askFrodo(prompt: String): String =
        askLocalFirst(prompt, frodoPersona, alibabaFallback = true, geminiFallback = false)

    fun askLegolasGimli(prompt: String): String {
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, legolasGimliPersona)
            if (!result.startsWith("Error:")) return result
        }
        val geminiResult = callExternalLlm(prompt, legolasGimliPersona, forcedGemini = true)
        if (!geminiResult.startsWith("Error:")) return geminiResult
        return fallbackToEmbedded("<|im_start|>system\n$legolasGimliPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
    }

    /**
     * Local-first inference pattern shared by Frodo and Samwise:
     * try embedded engine → Alibaba → optional Gemini → fallback to embedded.
     */
    private fun askLocalFirst(
        prompt: String,
        persona: String,
        alibabaFallback: Boolean,
        geminiFallback: Boolean
    ): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$persona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        if (alibabaFallback) {
            val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
            if (alibabaKey.isNotEmpty()) {
                val result = callAlibabaResponses(prompt, persona)
                if (!result.startsWith("Error:")) return result
            }
        }
        if (geminiFallback) {
            val result = callExternalLlm(prompt, persona)
            if (!result.startsWith("Error:")) return result
        }
        return fallbackToEmbedded("<|im_start|>system\n$persona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
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


    private fun callAlibabaResponses(prompt: String, persona: String, tools: List<String> = emptyList()): String {
        val apiKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: return "Error: No Alibaba API key"
        val model = "qwen-plus"

        val payload = mutableMapOf(
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
                val root = gson.fromJson(response.body(), JsonObject::class.java)
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
        val apiKey = CelebrimbotPasswordSafe.getApiKey(project) ?: ""
        val baseUrl = if (forcedGemini) "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
                      else return "Error: No external URL configured"
        val modelName = "gemini-1.5-flash"

        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))

        requestBuilder.header("x-goog-api-key", apiKey)
        val payload = mapOf(
            "systemInstruction" to mapOf(
                "parts" to listOf(mapOf("text" to persona))
            ),
            "contents" to listOf(
                mapOf("parts" to listOf(mapOf("text" to prompt)))
            )
        )

        val jsonPayload = gson.toJson(payload)
        val request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonPayload)).build()

        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                parseResponse(response.body())
            } else {
                "Error: HTTP ${response.statusCode()} - ${response.body()}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private fun parseResponse(json: String): String {
        return try {
            val root = gson.fromJson(json, JsonObject::class.java)
            // Gemini format: candidates[0].content.parts[0].text
            root.getAsJsonArray("candidates")
                ?.get(0)?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.get(0)?.asJsonObject
                ?.get("text")?.asString
                ?: "Unexpected response format"
        } catch (e: Exception) {
            "Error parsing response: ${e.message}"
        }
    }

    // ── Per-character dispatch ────────────────────────────────────────────────

    /**
     * Returns the result of asking [character]'s configured LLM [prompt] with
     * [persona] as the system instruction.
     *
     * The method reads the [AgentConfig] for [character] from settings and
     * dispatches to the correct backend:
     * - [CharacterProvider.LOCAL]        → embedded java-llama.cpp engine
     * - [CharacterProvider.ALIBABA_QWEN] → Alibaba DashScope Responses API
     * - [CharacterProvider.GOOGLE_GEMINI]→ Gemini generateContent API
     *
     * Falls back to the embedded engine if the configured backend is
     * unavailable (missing API key, network error, etc.).
     */
    data class LlmResponse(val text: String, val provider: String)

    fun askCharacter(character: String, prompt: String, persona: String, jsonPrefix: String = ""): String =
        askCharacterWithMeta(character, prompt, persona, jsonPrefix).text

    fun askCharacterWithMeta(character: String, prompt: String, persona: String, jsonPrefix: String = ""): LlmResponse {
        val cfg = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
            .getInstance(project).getAgentConfig(character)
        val fallbackPrompt = "<|im_start|>system\n$persona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n$jsonPrefix"

        return when (cfg.provider) {
            com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider.LOCAL -> {
                val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
                if (embeddedEngine.isModelDownloaded()) {
                    val formatted = "<|im_start|>system\n$persona<|im_end|>\n" +
                        "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n$jsonPrefix"
                    val raw = embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
                    LlmResponse(if (jsonPrefix.isNotEmpty()) jsonPrefix + raw else raw, "Local Qwen")
                } else {
                    LlmResponse(fallbackToEmbedded(fallbackPrompt), "Local Qwen (fallback)")
                }
            }
            com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider.ALIBABA_QWEN -> {
                val result = callAlibabaResponses(prompt, persona)
                if (!result.startsWith("Error:")) LlmResponse(result, "Alibaba Qwen")
                else LlmResponse(fallbackToEmbedded(fallbackPrompt), "Local Qwen (fallback)")
            }
            com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider.GOOGLE_GEMINI -> {
                val result = callExternalLlm(prompt, persona, forcedGemini = true)
                if (!result.startsWith("Error:")) LlmResponse(result, "Google Gemini")
                else LlmResponse(fallbackToEmbedded(fallbackPrompt), "Local Qwen (fallback)")
            }
            com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider.AMAZON_Q -> {
                val result = AmazonQCliProvider.getInstance(project).ask(prompt, persona)
                if (!result.startsWith("Error:")) LlmResponse(result, "Amazon Q")
                else {
                    logger.warn("Amazon Q failed for $character: $result")
                    val fallback = fallbackToEmbedded(fallbackPrompt)
                    LlmResponse(fallback, "Local Qwen (fallback — Amazon Q error: ${result.removePrefix("Error: ").take(80)})")
                }
            }
        }
    }

    companion object {
        fun getInstance(project: Project): CelebrimbotLlmService = project.service()

        fun loadPrompt(filename: String): String =
            CelebrimbotLlmService::class.java.getResourceAsStream("/prompts/$filename")
                ?.bufferedReader()
                ?.readText()
                ?.trim()
                ?: error("Prompt file not found: $filename")

        /**
         * Loads a prompt variant for the given provider.
         * For LOCAL, tries <name>_local.txt first, falls back to <name>.txt.
         * For all other providers, loads <name>.txt directly.
         */
        fun loadPromptForProvider(
            filename: String,
            provider: com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider
        ): String {
            if (provider == com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider.LOCAL) {
                val localFilename = filename.removeSuffix(".txt") + "_local.txt"
                val localPrompt = CelebrimbotLlmService::class.java.getResourceAsStream("/prompts/$localFilename")
                    ?.bufferedReader()?.readText()?.trim()
                if (!localPrompt.isNullOrBlank()) return localPrompt
            }
            return loadPrompt(filename)
        }
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
