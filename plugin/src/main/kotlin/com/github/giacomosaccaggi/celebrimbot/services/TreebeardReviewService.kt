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
    private fun askTreebeard(prompt: String): String =
        llmService.askCharacterWithMeta("Treebeard", prompt, treebeardPersona, "{").let {
            if (it.text.startsWith("{")) it.text else "{" + it.text
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
