package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTask
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

import com.github.giacomosaccaggi.celebrimbot.io.FileOperator
import com.github.giacomosaccaggi.celebrimbot.io.TerminalOperator
import com.github.giacomosaccaggi.celebrimbot.io.IdeFileOperator
import com.github.giacomosaccaggi.celebrimbot.io.IdeTerminalOperator
import com.github.giacomosaccaggi.celebrimbot.io.WebSearchOperator
import com.github.giacomosaccaggi.celebrimbot.io.DuckDuckGoSearchOperator
import com.github.giacomosaccaggi.celebrimbot.io.ProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.io.GitOperator
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessGitOperator
import com.github.giacomosaccaggi.celebrimbot.index.PalantirIndex
import com.github.giacomosaccaggi.celebrimbot.io.HeadlessShadowLogOperator
import com.github.giacomosaccaggi.celebrimbot.io.ShadowedFileOperator
import com.github.giacomosaccaggi.celebrimbot.io.TerminalResult
import com.github.giacomosaccaggi.celebrimbot.registry.ToolRegistry
import com.github.giacomosaccaggi.celebrimbot.registry.tools.*

@Service(Service.Level.PROJECT)
class CelebrimbotAgentOrchestrator(
    private val project: Project,
    private val fileOperator: FileOperator,
    private val terminalOperator: TerminalOperator,
    private val webSearchOperator: WebSearchOperator = DuckDuckGoSearchOperator(),
    private val projectScanOperator: ProjectScanOperator = HeadlessProjectScanOperator(project.basePath ?: ""),
    private val gitOperator: GitOperator = HeadlessGitOperator(project.basePath ?: "")
) {

    private val gson = Gson()
    private val llmService = CelebrimbotLlmService.getInstance(project)
    private val validationService = ValidationService.getInstance(project)
    private val treebeardService = TreebeardReviewService.getInstance(project)

    // The Shadow Log — backs up every scroll before it is touched
    private val shadowLog = HeadlessShadowLogOperator(project.basePath ?: "")

    // The Vault of the Rings — every tool the Fellowship can wield
    val toolRegistry = ToolRegistry().apply {
        register(ReadFileTool(fileOperator))
        register(WriteFileTool(fileOperator))
        register(DeleteFileTool(fileOperator))
        register(RunTerminalTool(terminalOperator))
        register(WebSearchTool(webSearchOperator))
        register(FetchPageTool(webSearchOperator))
        register(ListFilesTool(projectScanOperator))
        register(GrepFilesTool(projectScanOperator))
        register(FindFileTool(projectScanOperator))
        register(FileStatsTool(projectScanOperator))
        register(GitStatusTool(gitOperator))
        register(GitLogTool(gitOperator))
        register(GitDiffTool(gitOperator))
        register(GitBlameTool(gitOperator))
        register(GitBranchTool(gitOperator))
    }

    @Suppress("unused") // Called by IntelliJ service framework via project.service()
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
                    val aragornPrompt = "QUEST (this is what the user wants — focus on this):\n$userPrompt\n\nBACKGROUND CONTEXT (existing files in the project — do NOT reuse these paths for new files):\n$projectSkeleton"
                    val aragornJson = llmService.askAragorn(aragornPrompt, toolRegistry.toJsonSchema())
                    onStats?.invoke(1, 0)
                    log("[Aragorn → prompt] $aragornPrompt")
                    log("[Aragorn ← LLM] $aragornJson")
                    if (isSystemError(aragornJson)) {
                        onProgress("<i>[⚔️ Aragorn: local model unavailable, escalating to Elrond...]</i>")
                    } else {
                    val aragornTasks = parseAragornResult(aragornJson, userPrompt)
                    if (!aragornTasks.isNullOrEmpty()) {
                        onProgress("<i>[🌿 Samwise: executing ${aragornTasks.size} task(s)...]</i>")
                        // Open the Shadow Log before any scroll is touched
                        val sessionId = shadowLog.startSession()
                        log("[Shadow Log] Session opened: $sessionId")
                        val shadowedFileOp = ShadowedFileOperator(fileOperator, shadowLog)
                        var sharedContext = ""
                        var allSuccess = true
                        try {
                            for (task in aragornTasks) {
                                val result = executeTaskWithRetry(task, onProgress, sharedContext, userPrompt, projectSkeleton, shadowedFileOp)
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
                        } finally {
                            // Seal the manifest regardless of success or failure
                            shadowLog.endSession()
                            log("[Shadow Log] Session sealed: $sessionId")
                        }
                        if (allSuccess) {
                            treebeardAndBilbo(
                                tasks = aragornTasks,
                                userPrompt = userPrompt,
                                projectSkeleton = projectSkeleton,
                                shadowedFileOp = shadowedFileOp,
                                onProgress = onProgress,
                                onStats = onStats,
                                log = ::log
                            )
                            return@executeOnPooledThread
                        }
                        onProgress("<i>[🔄 Aragorn failed, escalating to Elrond & Celebrimbor...]</i>")
                    }
                }

                onProgress("<i>[🧙 Elrond: preparing the brief...]</i>")

                // Palantír: load the semantic index and query for the most relevant
                // files. Falls back to the raw project skeleton if the index is
                // missing, stale, or returns no results.
                val basePath = fileOperator.getProjectBasePath() ?: ""
                val palantir = PalantirIndex.loadOrNull(basePath)
                val relevantContext: String = if (palantir != null && !palantir.isStale(basePath)) {
                    val topFiles = palantir.query(userPrompt, topK = 8)
                    if (topFiles.isNotEmpty()) {
                        topFiles.mapIndexed { i, scored ->
                            val content = fileOperator.readFile(scored.entry.path).take(3000)
                            val score = String.format("%.1f", scored.score)
                            "${i + 1}. ${scored.entry.path} (score: $score)\n" +
                            "   Symbols: ${scored.entry.symbols.joinToString(", ")}\n" +
                            "   Content (first 3000 chars):\n$content"
                        }.joinToString("\n\n")
                    } else projectSkeleton
                } else projectSkeleton

                val elrondPrompt = buildString {
                    if (conversationHistory.isNotEmpty()) {
                        append("Conversation history:\n")
                        conversationHistory.forEach { (role, c) ->
                            append("$role: ${cleanForHistory(c)}\n")
                        }
                        append("\n")
                    }
                    append("QUEST (this is what the user wants — focus on this):\n$userPrompt\n\n")
                    if (relevantContext == projectSkeleton) {
                        append("BACKGROUND CONTEXT (existing files — do NOT reuse these paths for new files):\n$projectSkeleton")
                    } else {
                        append("RELEVANT FILES (by semantic relevance — background context only):\n$relevantContext")
                    }
                }
                val elrondJson = llmService.askElrond(elrondPrompt)
                onStats?.invoke(1, 0)
                log("[Elrond → prompt] $elrondPrompt")
                log("[Elrond ← LLM] $elrondJson")
                onProgress("<i>[💎 Celebrimbor: forging the plan...]</i>")
                val celebrimborPrompt = buildString {
                    // Pass Elrond's brief as plain text, capped to avoid token overflow.
                    // The local model truncates long inputs, so we sanitize and trim.
                    val elrondBrief = sanitizeJson(elrondJson).take(2000)
                    append("Brief from Elrond:\n$elrondBrief\n\n")
                    append("BACKGROUND CONTEXT (existing files — do NOT reuse these paths for new files):\n$projectSkeleton")
                }
                val celebrimborJson = llmService.askCelebrimbor(celebrimborPrompt, toolRegistry.toJsonSchema())
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
                        // Open the Shadow Log before Celebrimbor's plan touches any scroll
                        val sessionId = shadowLog.startSession()
                        log("[Shadow Log] Session opened: $sessionId")
                        val shadowedFileOp = ShadowedFileOperator(fileOperator, shadowLog)
                        var sharedContext = ""
                        try {
                            for (task in tasks) {
                                val result = executeTaskWithRetry(task, onProgress, sharedContext, userPrompt, projectSkeleton, shadowedFileOp)
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
                        } finally {
                            shadowLog.endSession()
                            log("[Shadow Log] Session sealed: $sessionId")
                        }
                        treebeardAndBilbo(
                            tasks = tasks,
                            userPrompt = userPrompt,
                            projectSkeleton = projectSkeleton,
                            shadowedFileOp = shadowedFileOp,
                            onProgress = onProgress,
                            onStats = onStats,
                            log = ::log
                        )
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

    /** Builds a Bilbo chronicle from a task list and emits it as a progress message. */
    private fun bilboSummary(tasks: List<CelebrimbotTask>, userPrompt: String, onProgress: (String) -> Unit) {
        val readFiles = tasks.filter { it.action == "read_psi" }.joinToString(", ") { it.target ?: "" }
        val writtenFiles = tasks.filter { it.action == "write_code" }.joinToString(", ") { it.target ?: "" }
        val deletedFiles = tasks.filter { it.action == "delete_file" }.joinToString(", ") { it.target ?: "" }
        val ranCommands = tasks.filter { it.action == "run_terminal" }.joinToString(", ") { it.command ?: "" }
        val summary = buildString {
            append("User asked: $userPrompt\n")
            if (readFiles.isNotEmpty()) append("Files read: $readFiles\n")
            if (writtenFiles.isNotEmpty()) append("Files written: $writtenFiles\n")
            if (deletedFiles.isNotEmpty()) append("Files deleted: $deletedFiles\n")
            if (ranCommands.isNotEmpty()) append("Commands run: $ranCommands\n")
        }
        onProgress("<b>[📖 Bilbo]</b> ${markdownToHtml(llmService.askBilbo(summary))}")
    }

    /**
     * The Treebeard Reflection Loop.
     *
     * After the Workers finish, Treebeard reviews the work against the original
     * quest. If he finds it incomplete, the missing tasks are sent back to
     * Celebrimbor for a new execution cycle. This repeats up to
     * [MAX_REFLECTION_LOOPS] times before Treebeard concedes and Bilbo
     * chronicles whatever was accomplished.
     */
    private fun treebeardAndBilbo(
        tasks: List<CelebrimbotTask>,
        userPrompt: String,
        projectSkeleton: String,
        shadowedFileOp: com.github.giacomosaccaggi.celebrimbot.io.FileOperator,
        onProgress: (String) -> Unit,
        onStats: ((Int, Int) -> Unit)?,
        log: (String) -> Unit
    ) {
        var currentTasks = tasks.toMutableList()
        var reflectionLoop = 0

        while (reflectionLoop < MAX_REFLECTION_LOOPS) {
            val executionSummary = buildExecutionSummary(currentTasks, userPrompt)
            onProgress("<i>[🌳 Treebeard: reviewing the work... (cycle ${reflectionLoop + 1}/$MAX_REFLECTION_LOOPS)]</i>")
            log("[Treebeard] Reviewing cycle ${reflectionLoop + 1}")

            val verdict = treebeardService.review(userPrompt, currentTasks, executionSummary)
            log("[Treebeard ← LLM] isComplete=${verdict.isComplete} reasoning=${verdict.reasoning}")

            if (verdict.isComplete) {
                onProgress("<i>[🌳 Treebeard: ✅ The work is complete. Hrum, hoom. Well done, young hobbits.]</i>")
                break
            }

            // Work is incomplete — report what is missing
            val missingList = verdict.additionalRequestsForPlanner
                .joinToString("<br>") { "&nbsp;&nbsp;• $it" }
            onProgress(
                "<i>[🌳 Treebeard: ⚠️ The work is not yet complete. Hrum. Missing deeds:<br>$missingList]</i>"
            )

            if (reflectionLoop == MAX_REFLECTION_LOOPS - 1) {
                // Loop limit reached — Treebeard concedes
                onProgress(
                    "<i>[🌳 Treebeard: I have waited long enough. The loop limit is reached. " +
                    "Bilbo shall chronicle what was done, and the unresolved matters are noted above.]</i>"
                )
                break
            }

            // Send missing tasks back to Celebrimbor
            onProgress("<i>[🌳 Treebeard: Sending missing tasks back to Celebrimbor...]</i>")
            val additionalPrompt = buildString {
                append("The following tasks were identified as missing by the reviewer.\n")
                append("Original quest: $userPrompt\n\n")
                append("Missing tasks to complete:\n")
                verdict.additionalRequestsForPlanner.forEachIndexed { i, task ->
                    append("${i + 1}. $task\n")
                }
            }

            val celebrimborPrompt = buildString {
                val elrondBrief = sanitizeJson(additionalPrompt).take(2000)
                append("Brief from Elrond:\n$elrondBrief\n\n")
                append("BACKGROUND CONTEXT (existing files — do NOT reuse these paths for new files):\n$projectSkeleton")
            }
            val celebrimborJson = llmService.askCelebrimbor(celebrimborPrompt, toolRegistry.toJsonSchema())
            onStats?.invoke(0, 1)
            log("[Treebeard re-plan → Celebrimbor] $celebrimborJson")

            val planResult = parsePlannerResult(celebrimborJson, userPrompt)
            if (planResult.strategy != "plan" || planResult.tasks.isEmpty()) {
                onProgress("<i>[🌳 Treebeard: Celebrimbor could not forge a new plan. Proceeding to Bilbo.]</i>")
                break
            }

            val newTasks = planResult.tasks
            onProgress("<i>[🔄 Workers: executing ${newTasks.size} additional task(s) (Treebeard cycle ${reflectionLoop + 1})]</i>")
            val sessionId = shadowLog.startSession()
            log("[Shadow Log] Treebeard session opened: $sessionId")
            var sharedContext = ""
            try {
                for (task in newTasks) {
                    val result = executeTaskWithRetry(task, onProgress, sharedContext, userPrompt, projectSkeleton, shadowedFileOp)
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
            } finally {
                shadowLog.endSession()
                log("[Shadow Log] Treebeard session sealed: $sessionId")
            }
            currentTasks.addAll(newTasks)
            reflectionLoop++
        }

        bilboSummary(currentTasks, userPrompt, onProgress)
    }

    /** Builds a human-readable execution summary for Treebeard's review. */
    private fun buildExecutionSummary(tasks: List<CelebrimbotTask>, userPrompt: String): String = buildString {
        append("User asked: $userPrompt\n")
        val written = tasks.filter { it.action == "write_code" }
        val deleted = tasks.filter { it.action == "delete_file" }
        val ran = tasks.filter { it.action == "run_terminal" }
        val read = tasks.filter { it.action == "read_psi" }
        if (written.isNotEmpty()) append("Files written: ${written.joinToString(", ") { it.target ?: "" }}\n")
        if (deleted.isNotEmpty()) append("Files deleted: ${deleted.joinToString(", ") { it.target ?: "" }}\n")
        if (ran.isNotEmpty()) append("Commands run: ${ran.joinToString(", ") { it.command ?: "" }}\n")
        if (read.isNotEmpty()) append("Files read: ${read.joinToString(", ") { it.target ?: "" }}\n")
        append("Total tasks executed: ${tasks.size}\n")
    }

    private fun executeTaskWithRetry(
        task: CelebrimbotTask,
        onProgress: (String) -> Unit,
        context: String = "",
        originalPrompt: String = "",
        projectSkeleton: String = "",
        shadowedFileOp: FileOperator? = null
    ): ActionResult {
        var retryCount = 0
        var lastError = ""
        while (retryCount < 3) {
            val workerLabel = when (task.action) {
                "write_code" -> if (task.worker == "legolas_gimli") "🏹🚧 Legolas & Gimli" else "🧙 Frodo"
                else -> "🌿 Samwise"
            }
            onProgress("<i>[$workerLabel → task ${task.id} - ${task.action} (attempt ${retryCount + 1})]</i>")
            val result = performAction(task, lastError, context, shadowedFileOp, onProgress)
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

    /**
     * Resolves a task action via the ToolRegistry. The One Ring (write_code)
     * is handled separately because it requires LLM interaction.
     * When a shadowedFileOp is provided it is used for write_code so that
     * the backup is recorded before the new content lands on disk.
     */
    private fun performAction(task: CelebrimbotTask, lastError: String, context: String = "", shadowedFileOp: FileOperator? = null, onProgress: (String) -> Unit = {}): ActionResult {
        val t = task.copy(target = task.target?.trimStart('/'))

        // The One Ring — write_code needs LLM, stays as a dedicated path
        if (t.action == "write_code") return writeCodeAction(t, lastError, context, shadowedFileOp, onProgress)

        val tool = toolRegistry.get(t.action)
            ?: return ActionResult(false, "Unknown action: ${t.action}")

        val args = buildMap {
            t.target?.let { put("target", it) }
            t.command?.let { put("command", it) }
            t.query?.let { put("query", it) }
            t.pattern?.let { put("pattern", it) }
            t.extension?.let { put("extension", it) }
            t.instruction?.let { put("instruction", it) }
        }

        val result = tool.execute(args)
        return ActionResult(result.success, result.output)
    }

    /**
     * The Council's Review — writes code then runs the build validator in a
     * hard-capped inner loop of [MAX_VALIDATION_CYCLES] cycles.
     *
     * Cycle flow:
     *   1. Ask Frodo / Legolas for code and write it to disk.
     *   2. Ask ValidationService for the build command.
     *      → null: skip validation, return success immediately.
     *   3. Run the command via TerminalOperator.
     *      → exit 0: return success.
     *      → exit != 0: extract the tail of the output, build a refinement
     *        prompt, ask the same worker to fix the code, write again, repeat.
     *   4. After MAX_VALIDATION_CYCLES failures: return ActionResult(false, lastOutput).
     *
     * This loop is entirely nested inside writeCodeAction and does NOT interact
     * with the outer executeTaskWithRetry retry counter.
     */
    private fun writeCodeAction(
        task: CelebrimbotTask,
        lastError: String,
        context: String = "",
        shadowedFileOp: FileOperator? = null,
        onProgress: (String) -> Unit = {}
    ): ActionResult {
        val targetPath = task.target ?: return ActionResult(false, "No target file specified")
        val effectiveFileOp = shadowedFileOp ?: fileOperator
        val useLegolasGimli = task.worker == "legolas_gimli"
        val basePath = fileOperator.getProjectBasePath() ?: ""
        val validationCmd = validationService.getValidationCommand(basePath, targetPath)

        // Build the initial worker prompt
        fun buildWorkerPrompt(currentError: String, currentFileContent: String): String = buildString {
            if (currentError.isNotEmpty()) {
                // Refinement cycle: give the worker the error and the broken code
                append("The code you wrote for $targetPath failed validation.\n")
                append("Error output:\n$currentError\n\n")
                append("Current file content:\n$currentFileContent\n\n")
                append("Fix the code.")
            } else {
                append("Task: \"${task.instruction}\"\nTarget file: $targetPath\n")
                if (context.isNotEmpty()) append("Current file content to modify:\n$context\n")
                if (lastError.isNotEmpty()) append("Previous attempt failed: $lastError. Fix it.\n")
            }
        }

        fun askWorker(prompt: String): String =
            if (useLegolasGimli) llmService.askLegolasGimli(prompt) else llmService.askFrodo(prompt)

        // ── Initial write ────────────────────────────────────────────────────
        val firstResponse = askWorker(buildWorkerPrompt("", ""))
        val firstCode = extractCode(firstResponse)
            ?: return ActionResult(false, "No code block found in Worker response.")
        if (!effectiveFileOp.writeFile(targetPath, firstCode))
            return ActionResult(false, "Failed to write code to $targetPath")

        // ── Skip validation if no command is available ───────────────────────
        if (validationCmd == null) {
            return ActionResult(true, "<b>Celebrimbot:</b> ✅ Code written to $targetPath")
        }

        // ── Council's Review inner loop ──────────────────────────────────────
        var lastOutput = ""
        repeat(MAX_VALIDATION_CYCLES) { cycle ->
            val cycleLabel = "${cycle + 1}/$MAX_VALIDATION_CYCLES"
            onProgress("<i>[🏛️ Council: validating $targetPath... (cycle $cycleLabel)]</i>")

            val termResult: TerminalResult = try {
                terminalOperator.executeCommand(validationCmd).get(30, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Terminal itself failed — treat as a build failure with the exception message
                TerminalResult(e.message ?: "Terminal error", -1)
            }

            if (termResult.exitCode == 0) {
                onProgress("<i>[🏛️ Council: ✅ build passed]</i>")
                return ActionResult(true, "<b>Celebrimbot:</b> ✅ Code written to $targetPath")
            }

            // Extract the tail of the merged output (compilation errors are always last)
            lastOutput = termResult.output
                .lines()
                .takeLast(80)
                .joinToString("\n")
                .take(2000)

            val errorLineCount = termResult.output.lines().count {
                it.contains("error", ignoreCase = true) || it.contains("Error") || it.contains("ERROR")
            }
            onProgress("<i>[🏛️ Council: build failed — $errorLineCount error line(s). Asking ${if (useLegolasGimli) "Legolas & Gimli" else "Frodo"} to fix... (cycle $cycleLabel)]</i>")

            // Read the broken file so the worker can see what it wrote
            val brokenContent = effectiveFileOp.readFile(targetPath)

            val fixResponse = askWorker(buildWorkerPrompt(lastOutput, brokenContent))
            val fixedCode = extractCode(fixResponse) ?: run {
                // Worker returned no code block — keep the last output as the error
                return@repeat
            }
            effectiveFileOp.writeFile(targetPath, fixedCode)
        }

        // All cycles exhausted
        return ActionResult(false, "Council's Review: build still failing after $MAX_VALIDATION_CYCLES cycles.\n$lastOutput")
    }

    companion object {
        private const val MAX_VALIDATION_CYCLES = 3
        private const val MAX_REFLECTION_LOOPS = 2  // Treebeard review cycles before conceding
        fun getInstance(project: Project): CelebrimbotAgentOrchestrator = project.service()
    }

    private fun parseAragornResult(json: String, @Suppress("UNUSED_PARAMETER") originalPrompt: String): List<CelebrimbotTask>? {
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

    private fun parsePlannerResult(json: String, originalPrompt: String = ""): PlannerResult {
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
            // Match each complete task object (handles both compact and pretty-printed JSON)
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


    data class PlannerResult(
        val strategy: String,
        val response: String = "",
        val tasks: List<CelebrimbotTask> = emptyList()
    )

    private fun cleanForHistory(text: String): String = text
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(Regex("\\[[^]]*]\\s*"), "")
        .replace(Regex("Celebrimbot:\\s*"), "")
        .replace(Regex("Could not parse plan[\\s\\S]*"), "[plan failed]")
        .trim()
        .take(300)

    private fun extractCode(text: String): String? =
        Regex("""```(?:\w+)?\n([\s\S]*?)```""").find(text)?.groupValues?.get(1)

    /**
     * Sanitizes raw LLM output before JSON parsing.
     * The local model often emits literal newlines inside string values,
     * which breaks Gson. This replaces unescaped newlines inside quoted
     * strings with a space, and strips markdown fences.
     */
    private fun sanitizeJson(raw: String): String {
        val stripped = raw.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        // Replace literal newlines inside JSON string values with a space
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

    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun markdownToHtml(text: String): String {
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

    private enum class RouteDecision { CHAT, EASY_TASK, COMPLEX_TASK }

    private fun route(prompt: String, history: List<Pair<String, String>>): RouteDecision {
        val lower = prompt.trim().lowercase()

        // Hard-coded pre-check: certain keywords always mean COMPLEX_TASK regardless
        // of what the local model says. These patterns reliably require multi-file plans
        // that Aragorn (1.5B) cannot produce reliably.
        val forceComplexPatterns = listOf(
            "pacchett", "package", "crea un pacchett", "crea il pacchett",
            "creamelo", "rifammelo", "refactor", "refactora", "ristruttura",
            "aggiungi a tutti", "add to all", "add to every", "modifica tutti",
            "sposta", "rinomina", "move", "rename"
        )
        if (forceComplexPatterns.any { lower.contains(it) }) return RouteDecision.COMPLEX_TASK

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
            heuristicRoute(lower)
        }
        // If the model timed out or returned a system error, fall back to heuristic
        val effective = if (raw.startsWith("SYSTEM:") || raw.startsWith("{SYSTEM") || raw.isBlank()) heuristicRoute(lower) else raw
        return when {
            isSimilarToPrevious -> RouteDecision.COMPLEX_TASK
            effective.startsWith("COMPLEX") -> RouteDecision.COMPLEX_TASK
            effective.startsWith("EASY") -> RouteDecision.EASY_TASK
            effective.startsWith("CHAT") -> RouteDecision.CHAT
            else -> RouteDecision.EASY_TASK
        }
    }

    private fun heuristicRoute(lower: String): String {
        val chatPatterns = listOf(
            "ciao", "hello", "hi", "hey", "salve", "buongiorno", "buonasera",
            "come stai", "how are you", "grazie", "thank", "prego", "ok", "okay",
            "cos'è", "cosa è", "what is", "spiegami", "explain", "dimmi", "tell me",
            "perché", "why", "come funziona", "how does"
        )
        val complexPatterns = listOf(
            "pacchett", "package", "refactor", "refactora", "ristruttura",
            "aggiungi a tutti", "modifica tutti", "sposta", "rinomina", "move", "rename"
        )
        return when {
            chatPatterns.any { lower.contains(it) } -> "CHAT"
            complexPatterns.any { lower.contains(it) } -> "COMPLEX_TASK"
            else -> "EASY_TASK"
        }
    }

    private fun isSystemError(s: String) = s.startsWith("{System:") || s.startsWith("System:") || s.contains("too slow or stuck")

    private fun similarityScore(a: String, b: String): Double {
        val words = a.split(" ").filter { it.length > 4 }
        if (words.isEmpty()) return 0.0
        return words.count { b.contains(it) }.toDouble() / words.size
    }

    data class ActionResult(val isSuccess: Boolean, val output: String)
}
