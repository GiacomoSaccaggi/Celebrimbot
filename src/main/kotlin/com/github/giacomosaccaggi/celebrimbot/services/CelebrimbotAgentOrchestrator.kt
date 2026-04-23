package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.io.File

import com.github.giacomosaccaggi.celebrimbot.io.FileOperator
import com.github.giacomosaccaggi.celebrimbot.io.TerminalOperator
import com.github.giacomosaccaggi.celebrimbot.io.LlmEngine
import com.github.giacomosaccaggi.celebrimbot.io.IdeFileOperator
import com.github.giacomosaccaggi.celebrimbot.io.IdeTerminalOperator
import com.github.giacomosaccaggi.celebrimbot.io.WebSearchOperator
import com.github.giacomosaccaggi.celebrimbot.io.DuckDuckGoSearchOperator
import com.github.giacomosaccaggi.celebrimbot.io.ProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.io.GitOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessGitOperator

@Service(Service.Level.PROJECT)
class CelebrimbotAgentOrchestrator(
    private val project: Project,
    private val fileOperator: FileOperator,
    private val terminalOperator: TerminalOperator,
    private val webSearchOperator: WebSearchOperator = DuckDuckGoSearchOperator(),
    private val projectScanOperator: ProjectScanOperator = HeadlessProjectScanOperator(project.basePath ?: ""),
    private val gitOperator: GitOperator = HeadlessGitOperator(project.basePath ?: ""),
    private val llmEngine: LlmEngine? = null
) {

    private val gson = Gson()
    private val llmService = CelebrimbotLlmService.getInstance(project)

    constructor(project: Project) : this(
        project,
        IdeFileOperator(project),
        IdeTerminalOperator(project)
    )

    fun executePlan(
        userPrompt: String,
        projectSkeleton: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onProgress: (String) -> Unit,
        onStats: ((localDelta: Int, plannerDelta: Int) -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
        onInternalLog: ((String) -> Unit)? = null
    ) {
        fun log(msg: String) { onInternalLog?.invoke(msg) }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val routeDecision = route(userPrompt, conversationHistory)

                if (routeDecision == RouteDecision.CHAT) {
                    val contextPrompt = buildString {
                        if (projectSkeleton.isNotEmpty()) append("Project context:\n$projectSkeleton\n\n")
                        if (conversationHistory.isNotEmpty()) {
                            append("Conversation so far:\n")
                            conversationHistory.forEach { (role, content) -> append("$role: $content\n") }
                            append("\n")
                        }
                        append("User: $userPrompt")
                    }
                    val response = llmService.askChat(contextPrompt)
                    onStats?.invoke(1, 0)
                    log("[Galadriel ← LLM] $response")
                    val cleanResponse = response
                        .removePrefix("[🧝 Galadriel] ")
                        .removePrefix("[🖥️ Local Qwen] ")
                        .removePrefix("[☁️ Alibaba Qwen] ")
                        .trim()
                    onProgress("<b>[🧝 Galadriel]</b> ${markdownToHtml(cleanResponse)}")
                    return@executeOnPooledThread
                }

                if (routeDecision == RouteDecision.EASY_TASK) {
                    onProgress("<i>[⚔️ Aragorn: preparing the task...]</i>")
                    val aragornPrompt = "User request: $userPrompt\n\nProject Skeleton:\n$projectSkeleton"
                    val aragornJson = llmService.askAragorn(aragornPrompt)
                    onStats?.invoke(1, 0)
                    log("[Aragorn → prompt] $aragornPrompt")
                    log("[Aragorn ← LLM] $aragornJson")
                    val aragornTasks = parseAragornResult(aragornJson, userPrompt)
                    if (!aragornTasks.isNullOrEmpty()) {
                        onProgress("<i>[🌿 Samwise: executing ${aragornTasks.size} task(s)...]</i>")
                        var sharedContext = ""
                        var allSuccess = true
                        for (task in aragornTasks) {
                            val result = executeTaskWithRetry(task, onProgress, sharedContext, userPrompt, projectSkeleton)
                            if (task.action == "read_psi" && result.isSuccess) {
                                sharedContext = result.output
                                onProgress("<i>[📄 Read ${task.target}: ${result.output.length} chars]</i>")
                            } else if (result.isSuccess && task.action in listOf(
                                    "web_search", "fetch_page", "list_files", "grep_files",
                                    "find_file", "file_stats", "git_status", "git_log",
                                    "git_diff", "git_blame", "git_branch"
                                )) {
                                sharedContext = result.output
                                onProgress("<b>Celebrimbot:</b> <pre>${escapeHtml(result.output)}</pre>")
                            }
                            if (!result.isSuccess) { allSuccess = false; break }
                        }
                        if (allSuccess) {
                            val readFiles = aragornTasks.filter { it.action == "read_psi" }.joinToString(", ") { it.target ?: "" }
                            val writtenFiles = aragornTasks.filter { it.action == "write_code" }.joinToString(", ") { it.target ?: "" }
                            val deletedFiles = aragornTasks.filter { it.action == "delete_file" }.joinToString(", ") { it.target ?: "" }
                            val ranCommands = aragornTasks.filter { it.action == "run_terminal" }.joinToString(", ") { it.command ?: "" }
                            val taskSummary = buildString {
                                append("User asked: $userPrompt\n")
                                if (readFiles.isNotEmpty()) append("Files read: $readFiles\n")
                                if (writtenFiles.isNotEmpty()) append("Files written: $writtenFiles\n")
                                if (deletedFiles.isNotEmpty()) append("Files deleted: $deletedFiles\n")
                                if (ranCommands.isNotEmpty()) append("Commands run: $ranCommands\n")
                            }
                            val bilboSummary = llmService.askBilbo(taskSummary)
                            onProgress("<b>[📖 Bilbo]</b> ${markdownToHtml(bilboSummary)}")
                            return@executeOnPooledThread
                        }
                        onProgress("<i>[🔄 Aragorn failed, escalating to Elrond & Celebrimbor...]</i>")
                    }
                }

                onProgress("<i>[🧙 Elrond: preparing the brief...]</i>")
                val elrondPrompt = buildString {
                    if (conversationHistory.isNotEmpty()) {
                        append("Conversation history:\n")
                        conversationHistory.forEach { (role, c) ->
                            append("$role: ${cleanForHistory(c)}\n")
                        }
                        append("\n")
                    }
                    append("Current request: $userPrompt\n\nProject Skeleton:\n$projectSkeleton")
                }
                val elrondJson = llmService.askElrond(elrondPrompt)
                onStats?.invoke(1, 0)
                log("[Elrond → prompt] $elrondPrompt")
                log("[Elrond ← LLM] $elrondJson")
                onProgress("<i>[💎 Celebrimbor: forging the plan...]</i>")
                val celebrimborPrompt = buildString {
                    append("Brief from Elrond:\n$elrondJson\n\n")
                    append("Project Skeleton:\n$projectSkeleton")
                }
                val celebrimborJson = llmService.askCelebrimbor(celebrimborPrompt)
                onStats?.invoke(0, 1)
                log("[Celebrimbor → prompt] $celebrimborPrompt")
                log("[Celebrimbor ← LLM] $celebrimborJson")
                val planResult = parsePlannerResult(celebrimborJson, userPrompt)

                when (planResult.strategy) {
                    "direct" -> {
                        onProgress("<b>Celebrimbot:</b> ${planResult.response}")
                    }
                    "plan" -> {
                        val tasks = planResult.tasks
                        if (tasks.isEmpty()) {
                            onProgress("<b>Celebrimbot:</b> Could not generate a valid plan. Raw: <code>${celebrimborJson.take(300)}</code>")
                            return@executeOnPooledThread
                        }
                        onProgress("<i>[🔄 Workers: executing ${tasks.size} task(s)...]</i>")
                        var sharedContext = ""
                        for (task in tasks) {
                            val result = executeTaskWithRetry(task, onProgress, sharedContext, userPrompt, projectSkeleton)
                            if (task.action == "read_psi" && result.isSuccess) {
                                sharedContext = result.output
                                onProgress("<i>[📄 Read ${task.target}: ${result.output.length} chars]</i>")
                            } else if (result.isSuccess && task.action in listOf(
                                    "web_search", "fetch_page", "list_files", "grep_files",
                                    "find_file", "file_stats", "git_status", "git_log",
                                    "git_diff", "git_blame", "git_branch"
                                )) {
                                sharedContext = result.output
                                onProgress("<b>Celebrimbot:</b> <pre>${escapeHtml(result.output)}</pre>")
                            }
                        }
                        val readFiles = tasks.filter { it.action == "read_psi" }.joinToString(", ") { it.target ?: "" }
                        val writtenFiles = tasks.filter { it.action == "write_code" }.joinToString(", ") { it.target ?: "" }
                        val deletedFiles = tasks.filter { it.action == "delete_file" }.joinToString(", ") { it.target ?: "" }
                        val ranCommands = tasks.filter { it.action == "run_terminal" }.joinToString(", ") { it.command ?: "" }
                        val complexSummary = buildString {
                            append("User asked: $userPrompt\n")
                            if (readFiles.isNotEmpty()) append("Files read: $readFiles\n")
                            if (writtenFiles.isNotEmpty()) append("Files written: $writtenFiles\n")
                            if (deletedFiles.isNotEmpty()) append("Files deleted: $deletedFiles\n")
                            if (ranCommands.isNotEmpty()) append("Commands run: $ranCommands\n")
                        }
                        val bilboSummary = llmService.askBilbo(complexSummary)
                        onProgress("<b>[📖 Bilbo]</b> ${markdownToHtml(bilboSummary)}")
                    }
                    else -> onProgress("<b>Celebrimbot:</b> Could not parse plan. Raw: <code>${celebrimborJson.take(300)}</code>")
                }
            } catch (e: Exception) {
                onProgress("<b>Error:</b> ${e.message}")
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private fun executeTaskWithRetry(
        task: CelebrimbotTask,
        onProgress: (String) -> Unit,
        context: String = "",
        originalPrompt: String = "",
        projectSkeleton: String = ""
    ): ActionResult {
        var retryCount = 0
        var lastError = ""
        while (retryCount < 3) {
            val workerLabel = when (task.action) {
                "write_code" -> if (task.worker == "legolas_gimli") "🏹🚧 Legolas & Gimli" else "🧙 Frodo"
                else -> "🌿 Samwise"
            }
            onProgress("<i>[$workerLabel → task ${task.id} - ${task.action} (attempt ${retryCount + 1})]</i>")
            val result = performAction(task, lastError, context)
            if (result.isSuccess) {
                if (result.output.isNotEmpty() && task.action != "read_psi") onProgress(result.output)
                return result
            }
            retryCount++
            lastError = result.output
            onProgress("<i>[Task ${task.id} failed: $lastError]</i>")
        }
        // Escalate to planner after 3 failures
        onProgress("<i>[🔄 Escalating task ${task.id} to Planner...]</i>")
        val escalationPrompt = buildString {
            append("Task failed 3 times: ${task.action} on ${task.target}.\n")
            append("Error: $lastError\n")
            append("Original request: $originalPrompt\n")
            if (context.isNotEmpty()) append("File content:\n$context\n")
            if (projectSkeleton.isNotEmpty()) append("Project:\n$projectSkeleton\n")
            append("Provide a direct fix or alternative plan.")
        }
        val plannerResponse = llmService.askElrond(escalationPrompt)
        val escalationResult = parsePlannerResult(plannerResponse)
        if (escalationResult.strategy == "direct" && escalationResult.response.isNotEmpty()) {
            onProgress("<b>Celebrimbot (Planner fallback):</b> ${escalationResult.response}")
        }
        return ActionResult(false, lastError)
    }

    private fun performAction(task: CelebrimbotTask, lastError: String, context: String = ""): ActionResult {
        val t = task.copy(target = task.target?.trimStart('/'))
        return when (t.action) {
            "read_psi"     -> readPsiAction(t)
            "write_code"   -> writeCodeAction(t, lastError, context)
            "delete_file"  -> deleteFileAction(t)
            "run_terminal" -> runTerminalAction(t)
            "web_search"   -> webSearchAction(t)
            "fetch_page"   -> fetchPageAction(t)
            "list_files"   -> listFilesAction(t)
            "grep_files"   -> grepFilesAction(t)
            "find_file"    -> findFileAction(t)
            "file_stats"   -> fileStatsAction(t)
            "git_status"   -> ActionResult(true, gitOperator.status())
            "git_log"      -> ActionResult(true, gitOperator.log())
            "git_diff"     -> ActionResult(true, gitOperator.diff(t.target))
            "git_blame"    -> ActionResult(true, gitOperator.blame(t.target ?: ""))
            "git_branch"   -> ActionResult(true, gitOperator.branch())
            else           -> ActionResult(false, "Unknown action: ${t.action}")
        }
    }

    private fun readPsiAction(task: CelebrimbotTask): ActionResult {
        val content = fileOperator.readFile(task.target ?: "")
        return ActionResult(!content.startsWith("Error:"), content)
    }

    private fun deleteFileAction(task: CelebrimbotTask): ActionResult {
        val target = task.target ?: return ActionResult(false, "No target file specified")
        val success = fileOperator.deleteFile(target)
        return if (success) ActionResult(true, "<b>Celebrimbot:</b> ✅ Deleted $target")
        else ActionResult(false, "Failed to delete $target")
    }

    private fun webSearchAction(task: CelebrimbotTask): ActionResult {
        val query = task.query ?: task.instruction ?: return ActionResult(false, "No search query provided")
        val result = webSearchOperator.search(query)
        return ActionResult(!result.startsWith("Error:"), result)
    }

    private fun fetchPageAction(task: CelebrimbotTask): ActionResult {
        val url = task.target ?: return ActionResult(false, "No URL provided")
        val result = webSearchOperator.fetchPage(url)
        return ActionResult(!result.startsWith("Error:"), result)
    }

    private fun listFilesAction(task: CelebrimbotTask): ActionResult {
        val result = projectScanOperator.listFiles(task.target, task.extension)
        return ActionResult(!result.startsWith("Error:"), result)
    }

    private fun grepFilesAction(task: CelebrimbotTask): ActionResult {
        val pattern = task.pattern ?: return ActionResult(false, "No grep pattern provided")
        val result = projectScanOperator.grepFiles(pattern, task.extension)
        return ActionResult(!result.startsWith("Error:"), result)
    }

    private fun findFileAction(task: CelebrimbotTask): ActionResult {
        val name = task.query ?: task.target ?: return ActionResult(false, "No file name provided")
        val result = projectScanOperator.findByName(name)
        return ActionResult(!result.startsWith("Error:"), result)
    }

    private fun fileStatsAction(task: CelebrimbotTask): ActionResult {
        val target = task.target ?: return ActionResult(false, "No target file specified")
        val result = projectScanOperator.fileStats(target)
        return ActionResult(!result.startsWith("Error:"), result)
    }

    private fun writeCodeAction(task: CelebrimbotTask, lastError: String, context: String = ""): ActionResult {
        val workerPrompt = buildString {
            append("Task: \"${task.instruction}\"\nTarget file: ${task.target}\n")
            if (context.isNotEmpty()) append("Current file content to modify:\n$context\n")
            if (lastError.isNotEmpty()) append("Previous attempt failed: $lastError. Fix it.\n")
        }

        val useLegolasGimli = task.worker == "legolas_gimli"
        val codeResponse = when {
            useLegolasGimli -> llmService.askLegolasGimli(workerPrompt)
            else -> llmService.askFrodo(workerPrompt)
        }
        val code = extractCode(codeResponse) ?: return ActionResult(false, "No code block found in Worker response.")

        val targetPath = task.target ?: return ActionResult(false, "No target file specified")
        
        val success = fileOperator.writeFile(targetPath, code)
        
        return if (success) ActionResult(true, "<b>Celebrimbot:</b> ✅ Code written to $targetPath")
        else ActionResult(false, "Failed to write code to $targetPath")
    }

    private fun runTerminalAction(task: CelebrimbotTask): ActionResult {
        val command = task.command ?: return ActionResult(false, "No command provided")
        return try {
            val output = terminalOperator.executeCommand(command).get(30, java.util.concurrent.TimeUnit.SECONDS)
            if (output.contains("ERROR") || output.contains("failed")) ActionResult(false, output)
            else ActionResult(true, output)
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Terminal execution failed")
        }
    }

    private fun parseAragornResult(json: String, originalPrompt: String): List<CelebrimbotTask>? {
        return try {
            val raw = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val jsonStr = Regex("""\{[\s\S]*\}""").find(raw)?.value ?: raw
            val obj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val type = object : TypeToken<List<CelebrimbotTask>>() {}.type
            val tasks: List<CelebrimbotTask>? = gson.fromJson(obj.getAsJsonArray("tasks"), type)
            if (!tasks.isNullOrEmpty()) tasks
            else {
                // fallback: single task object under "task" key
                val taskObj = obj.getAsJsonObject("task")
                if (taskObj != null) listOf(gson.fromJson(taskObj, CelebrimbotTask::class.java)) else null
            }
        } catch (_: Exception) { null }
    }

        private fun parseStewardResult(json: String, fallback: List<CelebrimbotTask>): List<CelebrimbotTask> {
        return try {
            val raw = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val jsonStr = Regex("""\{[\s\S]*\}""").find(raw)?.value ?: raw
            val obj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val type = object : TypeToken<List<CelebrimbotTask>>() {}.type
            val tasks: List<CelebrimbotTask> = gson.fromJson(obj.getAsJsonArray("tasks"), type) ?: emptyList()
            if (tasks.isNotEmpty()) tasks else fallback
        } catch (_: Exception) {
            fallback
        }
    }

    private fun parsePlannerResult(json: String, originalPrompt: String = ""): PlannerResult {
        try {
            val raw = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            // Try to find a complete JSON object first
            val jsonStr = Regex("""\{[\s\S]*\}""").find(raw)?.value ?: raw
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
            // Match each complete task object (handles both compact and pretty-printed JSON)
            val taskObjPattern = Regex("""\{[^{}]*"action"\s*:\s*"[^"]+"[^{}]*\}""")
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

    internal fun inferPlanFromPrompt(prompt: String): PlannerResult {
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
            val fileMatch = Regex("(?:file\\s+)?([\\w./\\-]+\\.\\w+)").find(prompt)
            val target = fileMatch?.groupValues?.get(1) ?: return PlannerResult("unknown")
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

    private fun parsePlan(json: String): List<CelebrimbotTask> {
        return try {
            val raw = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val jsonStr = Regex("""[\s\S]*?(\[[\s\S]*])[\s\S]*""").find(raw)?.groupValues?.get(1) ?: raw
            val type = object : TypeToken<List<CelebrimbotTask>>() {}.type
            gson.fromJson<List<CelebrimbotTask>>(jsonStr, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class PlannerResult(
        val strategy: String,
        val response: String = "",
        val tasks: List<CelebrimbotTask> = emptyList()
    )

    private fun cleanForHistory(text: String): String = text
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("\\[[^\\]]*\\]\\s*"), "")
        .replace(Regex("Celebrimbot:\\s*"), "")
        .replace(Regex("Could not parse plan[\\s\\S]*"), "[plan failed]")
        .trim()
        .take(300)

    private fun extractCode(text: String): String? =
        Regex("""```(?:\w+)?\n([\s\S]*?)```""").find(text)?.groupValues?.get(1)

    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun markdownToHtml(text: String): String {
        var html = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        html = Regex("```[\\w]*\\n([\\s\\S]*?)```").replace(html) { "<pre><code>${it.groupValues[1]}</code></pre>" }
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

    private enum class RouteDecision { CHAT, EASY_TASK, COMPLEX_TASK }

    private fun route(prompt: String, history: List<Pair<String, String>>): RouteDecision {
        val lower = prompt.trim().lowercase()
        val recentUserMessages = history.takeLast(8).filter { it.first == "User" }.map { it.second.trim().lowercase() }
        val isSimilarToPrevious = recentUserMessages.any { prev ->
            prev != lower && prev.length > 15 && lower.length > 15 &&
                (prev.take(30) == lower.take(30) || similarityScore(prev, lower) > 0.8)
        }
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        val raw = if (embeddedEngine.isModelDownloaded()) {
            val historyContext = if (history.isNotEmpty())
                "Conversation history:\n" + history.takeLast(6).joinToString("\n") { (r, c) -> "$r: $c" } + "\n\n"
            else ""
            val routingPrompt = "<|im_start|>system\n" + CelebrimbotLlmService.loadPrompt("gandalf_system_prompt.txt") + "<|im_end|>\n" +
                "<|im_start|>user\n" + historyContext + "Current request: " + prompt + "<|im_end|>\n<|im_start|>assistant\n"
            embeddedEngine.askQuestion(routingPrompt, stopStrings = listOf("<|im_end|>", "<|im_start|>", "\n")).trim().uppercase()
        } else {
            if (lower.length < 60) "CHAT" else "EASY_TASK"
        }
        return when {
            isSimilarToPrevious -> RouteDecision.COMPLEX_TASK
            raw.startsWith("COMPLEX") -> RouteDecision.COMPLEX_TASK
            raw.startsWith("EASY") -> RouteDecision.EASY_TASK
            raw.startsWith("CHAT") -> RouteDecision.CHAT
            else -> RouteDecision.EASY_TASK
        }
    }

    private fun similarityScore(a: String, b: String): Double {
        val words = a.split(" ").filter { it.length > 4 }
        if (words.isEmpty()) return 0.0
        return words.count { b.contains(it) }.toDouble() / words.size
    }

        data class ActionResult(val isSuccess: Boolean, val output: String)

    companion object {
        fun getInstance(project: Project): CelebrimbotAgentOrchestrator = project.service()
    }
}
