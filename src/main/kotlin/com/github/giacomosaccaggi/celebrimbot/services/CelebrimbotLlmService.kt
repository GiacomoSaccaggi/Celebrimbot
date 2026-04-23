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

    fun askAragorn(prompt: String): String {
        val aragornPersona = loadPrompt("aragorn_system_prompt.txt")
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

    fun askCelebrimbor(prompt: String): String {
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, celebrimborPersona)
            if (!result.startsWith("Error:")) return result
        }
        val geminiResult = callExternalLlm(prompt, celebrimborPersona, forcedGemini = true)
        if (!geminiResult.startsWith("Error:")) return geminiResult
        val simplifiedPrompt = "<|im_start|>system\n$celebrimborPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{"
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

    fun askFrodo(prompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$frodoPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, frodoPersona)
            if (!result.startsWith("Error:")) return result
        }
        return fallbackToEmbedded("<|im_start|>system\n$frodoPersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
    }

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

    fun askSamwise(prompt: String): String {
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$samwisePersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
            return embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        val alibabaKey = CelebrimbotPasswordSafe.getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            val result = callAlibabaResponses(prompt, samwisePersona)
            if (!result.startsWith("Error:")) return result
        }
        val result = callExternalLlm(prompt, samwisePersona)
        if (!result.startsWith("Error:")) return result
        return fallbackToEmbedded("<|im_start|>system\n$samwisePersona<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
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
        return askSamwise(prompt) // Keep backward compatibility or refactor later
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

        fun loadPrompt(filename: String): String =
            CelebrimbotLlmService::class.java.getResourceAsStream("/prompts/$filename")
                ?.bufferedReader()
                ?.readText()
                ?.trim()
                ?: error("Prompt file not found: $filename")
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
