package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTask
import com.github.giacomosaccaggi.celebrimbot.model.TreebeardReviewResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Treebeard, the Ent Reviewer — never hasty, always thorough.
 *
 * After the Workers complete their deeds, Treebeard inspects the original
 * quest and the execution results to decide whether the Fellowship's work
 * truly satisfies the user's desire. If it does not, he lists the missing
 * tasks so the Planner can forge them anew.
 */
@Service(Service.Level.PROJECT)
class TreebeardReviewService(private val project: Project) {

    private val gson = Gson()
    private val llmService = CelebrimbotLlmService.getInstance(project)
    private val treebeardPersona = CelebrimbotLlmService.loadPrompt("treebeard_system_prompt.txt")

    /**
     * Asks Treebeard to review the completed work.
     *
     * @param originalRequest  the user's original prompt, verbatim.
     * @param executedTasks    the list of tasks that were actually executed.
     * @param executionSummary a human-readable summary of what was done
     *        (files written, commands run, outputs produced).
     * @return a [TreebeardReviewResult] parsed from the LLM's JSON response,
     *         or a safe fallback that marks the work as complete if parsing fails.
     */
    fun review(
        originalRequest: String,
        executedTasks: List<CelebrimbotTask>,
        executionSummary: String
    ): TreebeardReviewResult {
        val taskDescriptions = executedTasks.joinToString("\n") { task ->
            "  - [${task.action}] target=${task.target ?: "n/a"} instruction=${task.instruction ?: task.command ?: task.query ?: ""}"
        }

        val reviewPrompt = buildString {
            append("ORIGINAL QUEST (what the user asked for):\n$originalRequest\n\n")
            append("TASKS EXECUTED BY THE FELLOWSHIP:\n$taskDescriptions\n\n")
            append("EXECUTION SUMMARY:\n$executionSummary\n\n")
            append("Now deliver your verdict as a JSON object.")
        }

        val raw = askTreebeard(reviewPrompt)
        return parseReviewResult(raw)
    }

    // ── LLM call ──────────────────────────────────────────────────────────────

    /**
     * Treebeard prefers the cloud planner (Alibaba → Gemini) for his deep
     * reflection, but falls back to the local model if needed.
     */
    private fun askTreebeard(prompt: String): String {
        val alibabaKey = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotPasswordSafe
            .getAlibabaApiKey(project) ?: ""
        if (alibabaKey.isNotEmpty()) {
            // Reuse the Alibaba path via a thin wrapper — Treebeard uses the same
            // HTTP infrastructure as Celebrimbor.
            val result = callAlibabaForTreebeard(prompt)
            if (!result.startsWith("Error:")) return result
        }
        // Fallback: local embedded engine
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        if (embeddedEngine.isModelDownloaded()) {
            val formatted = "<|im_start|>system\n$treebeardPersona<|im_end|>\n" +
                "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n{"
            return "{" + embeddedEngine.askQuestion(formatted, stopStrings = listOf("<|im_end|>", "<|im_start|>"))
        }
        // Last resort: return a safe "complete" verdict so the flow is never blocked
        return """{"isComplete":true,"reasoning":"Treebeard could not be reached. The work is assumed complete.","additionalRequestsForPlanner":[]}"""
    }

    private fun callAlibabaForTreebeard(prompt: String): String {
        val apiKey = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotPasswordSafe
            .getAlibabaApiKey(project) ?: return "Error: No Alibaba API key"
        val model = "qwen-plus"

        val payload = mapOf(
            "model" to model,
            "input" to mapOf(
                "messages" to listOf(
                    mapOf("role" to "system", "content" to treebeardPersona),
                    mapOf("role" to "user", "content" to prompt)
                )
            )
        )

        val httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build()
        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create("https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/text-generation/generation"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(java.time.Duration.ofSeconds(45))
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(gson.toJson(payload)))
            .build()

        return try {
            val response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val root = gson.fromJson(response.body(), JsonObject::class.java)
                root.getAsJsonObject("output")
                    ?.getAsJsonArray("choices")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("message")
                    ?.get("content")?.asString
                    ?: "Error: Unexpected Alibaba response format"
            } else {
                "Error: HTTP ${response.statusCode()}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ── JSON parsing ──────────────────────────────────────────────────────────

    private fun parseReviewResult(raw: String): TreebeardReviewResult {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val jsonStr = Regex("""\{[\s\S]*}""").find(cleaned)?.value ?: cleaned
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)

            val isComplete = obj.get("isComplete")?.asBoolean ?: true
            val reasoning = obj.get("reasoning")?.asString ?: "No reasoning provided."
            val additionalType = object : TypeToken<List<String>>() {}.type
            val additional: List<String> = try {
                gson.fromJson(obj.getAsJsonArray("additionalRequestsForPlanner"), additionalType)
                    ?: emptyList()
            } catch (_: Exception) { emptyList() }

            TreebeardReviewResult(isComplete, reasoning, additional)
        } catch (_: Exception) {
            // If parsing fails, assume complete so the flow is never permanently blocked
            TreebeardReviewResult(
                isComplete = true,
                reasoning = "Treebeard's verdict could not be parsed. Proceeding to Bilbo.",
                additionalRequestsForPlanner = emptyList()
            )
        }
    }

    companion object {
        fun getInstance(project: Project): TreebeardReviewService = project.service()
    }
}
