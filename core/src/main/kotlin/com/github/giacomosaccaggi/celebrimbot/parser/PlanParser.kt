package com.github.giacomosaccaggi.celebrimbot.parser

import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class PlannerResult(
    val strategy: String,
    val response: String = "",
    val tasks: List<CelebrimbotTask> = emptyList()
)

object PlanParser {

    val gson = Gson()

    fun parseAragornResult(json: String, @Suppress("UNUSED_PARAMETER") originalPrompt: String): List<CelebrimbotTask>? {
        return try {
            val raw = sanitizeJson(json)
            val jsonStr = Regex("""\{[\s\S]*}""").find(raw)?.value ?: raw
            val obj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val type = object : TypeToken<List<CelebrimbotTask>>() {}.type
            val tasks: List<CelebrimbotTask>? = gson.fromJson(obj.getAsJsonArray("tasks"), type)
            if (!tasks.isNullOrEmpty()) tasks
            else {
                val taskObj = obj.getAsJsonObject("task")
                if (taskObj != null) listOf(gson.fromJson(taskObj, CelebrimbotTask::class.java)) else null
            }
        } catch (_: Exception) { null }
    }

    fun parsePlannerResult(json: String, originalPrompt: String = ""): PlannerResult {
        try {
            val raw = sanitizeJson(json)
            val jsonStr = Regex("""\{[\s\S]*}""").find(raw)?.value ?: raw
            val obj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val strategy = obj.get("strategy")?.asString ?: "unknown"
            when (strategy) {
                "direct" -> return PlannerResult("direct", response = obj.get("response")?.asString ?: "")
                "plan" -> {
                    val type = object : TypeToken<List<CelebrimbotTask>>() {}.type
                    val tasks: List<CelebrimbotTask> = gson.fromJson(obj.getAsJsonArray("tasks"), type) ?: emptyList()
                    if (tasks.isNotEmpty()) return PlannerResult("plan", tasks = tasks)
                }
            }
        } catch (_: Exception) {}
        // Fallback: try to salvage partial tasks from truncated JSON
        try {
            val partialTasks = mutableListOf<CelebrimbotTask>()
            val taskObjPattern = Regex("""\{[^{}]*"action"\s*:\s*"[^"]+"[^{}]*}""")
            taskObjPattern.findAll(json).forEach { match ->
                try {
                    val task = gson.fromJson(match.value, CelebrimbotTask::class.java)
                    if (task?.action != null) partialTasks.add(task)
                } catch (_: Exception) {}
            }
            if (partialTasks.isNotEmpty()) return PlannerResult("plan", tasks = partialTasks)
        } catch (_: Exception) {}
        return inferPlanFromPrompt(originalPrompt)
    }

    fun inferPlanFromPrompt(prompt: String): PlannerResult {
        val lower = prompt.trim().lowercase()
        val deletePatterns = listOf("elimin", "cancell", "rimuov", "delete", "remove")
        val searchPatterns = listOf("cerca", "search", "find", "look up", "dimmi di", "tell me about")
        val listPatterns = listOf("lista file", "list files", "quali file", "what files", "show files")
        val gitPatterns = listOf("git status", "git log", "git diff", "git branch", "commit", "branch")
        val writePatterns = listOf(
            "crea", "creami", "crei", "scrivi", "scrivimi", "genera", "generami", "generi",
            "implementa", "implementami", "fammi", "make", "create", "write", "generate",
            "give me", "produce", "costruisci"
        )

        if (deletePatterns.any { lower.contains(it) }) {
            val fileMatch = Regex("(?:^|\\s)([\\w][\\w./\\-]{0,60}\\.\\w{1,10})(?:\\s|$)").find(prompt)
            val target = fileMatch?.groupValues?.get(1)
            if (target == null || target.startsWith("/") || target.count { it == '/' } > 3)
                return PlannerResult("unknown")
            return PlannerResult("plan", tasks = listOf(CelebrimbotTask(1, "delete_file", target = target)))
        }
        if (searchPatterns.any { lower.contains(it) }) {
            return PlannerResult("plan", tasks = listOf(CelebrimbotTask(1, "web_search", query = prompt.trim())))
        }
        if (listPatterns.any { lower.contains(it) }) {
            return PlannerResult("plan", tasks = listOf(CelebrimbotTask(1, "list_files")))
        }
        if (gitPatterns.any { lower.contains(it) }) {
            val action = when {
                lower.contains("log") -> "git_log"
                lower.contains("diff") -> "git_diff"
                lower.contains("branch") -> "git_branch"
                else -> "git_status"
            }
            return PlannerResult("plan", tasks = listOf(CelebrimbotTask(1, action)))
        }
        if (writePatterns.any { lower.contains(it) }) {
            val fileMatch = Regex("([\\w./\\-]+\\.\\w+)").find(prompt)
            val target = fileMatch?.groupValues?.get(1) ?: "src/output.py"
            return PlannerResult("plan", tasks = listOf(
                CelebrimbotTask(1, "write_code", target = target, instruction = prompt)
            ))
        }
        return PlannerResult("unknown")
    }

    fun sanitizeJson(raw: String): String {
        val stripped = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val sb = StringBuilder(stripped.length)
        var inString = false
        var i = 0
        while (i < stripped.length) {
            val c = stripped[i]
            when {
                c == '\\' && inString -> { sb.append(c); i++; if (i < stripped.length) sb.append(stripped[i]) }
                c == '"' -> { inString = !inString; sb.append(c) }
                (c == '\n' || c == '\r') && inString -> sb.append(' ')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    fun extractCode(text: String): String? =
        Regex("""```(?:\w+)?\n([\s\S]*?)```""").find(text)?.groupValues?.get(1)

    fun markdownToHtml(text: String): String {
        var html = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        html = Regex("```\\w*\\n([\\s\\S]*?)```").replace(html) { "<pre><code>${it.groupValues[1]}</code></pre>" }
        html = Regex("`([^`]+)`").replace(html) { "<code>${it.groupValues[1]}</code>" }
        html = Regex("\\*\\*([^*]+)\\*\\*").replace(html) { "<b>${it.groupValues[1]}</b>" }
        html = Regex("\\*([^*]+)\\*").replace(html) { "<i>${it.groupValues[1]}</i>" }
        html = html.lines().joinToString("\n") { line ->
            when {
                line.trimStart().startsWith("### ") -> "<b>${line.trimStart().removePrefix("### ")}</b>"
                line.trimStart().startsWith("## ")  -> "<b>${line.trimStart().removePrefix("## ")}</b>"
                line.trimStart().startsWith("# ")   -> "<b>${line.trimStart().removePrefix("# ")}</b>"
                line.trimStart().startsWith("- ")   -> "&nbsp;&nbsp;• ${line.trimStart().removePrefix("- ")}"
                line.trimStart().matches(Regex("\\d+\\. .*")) -> {
                    val num = line.trimStart().substringBefore(". ")
                    "&nbsp;&nbsp;<b>$num.</b> ${line.trimStart().substringAfter(". ")}"
                }
                else -> line
            }
        }
        html = Regex("\\[([^]]+)]\\(([^)]+)\\)").replace(html) { "<a href='${it.groupValues[2]}'>${it.groupValues[1]}</a>" }
        html = html.replace("\n", "<br>")
        return html
    }

    fun escapeHtml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun cleanForHistory(text: String): String = text
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("\\[[^]]*]\\s*"), "")
        .replace(Regex("Celebrimbot:\\s*"), "")
        .replace(Regex("Could not parse plan[\\s\\S]*"), "[plan failed]")
        .trim()
        .take(300)
}
