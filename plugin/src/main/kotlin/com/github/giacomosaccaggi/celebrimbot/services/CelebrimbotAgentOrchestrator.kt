package com.github.giacomosaccaggi.celebrimbot.services

import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTask
import com.github.giacomosaccaggi.celebrimbot.parser.PlanParser
import com.github.giacomosaccaggi.celebrimbot.parser.PlannerResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*

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
) : Disposable {

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun dispose() {
        coroutineScope.cancel()
    }

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
        register(RunTerminalTool(terminalOperator, com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState.getInstance(project).state.terminalTimeoutSeconds))
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
        coroutineScope.launch {
            try {
                val settings = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState.getInstance(project)

                val easyEnabled = settings.state.easyTaskEnabled
                val complexEnabled = settings.state.complexTaskEnabled

                // Helper: check if a character is set to CLI provider
                fun isCli(character: String) = settings.getAgentConfig(character).provider ==
                    com.github.giacomosaccaggi.celebrimbot.settings.CharacterProvider.CLI

                // If Gandalf is CLI, delegate the entire request to CLI (it does its own routing)
                if (isCli("Gandalf")) {
                    onProgress("<i>[🧙 Gandalf (CLI): delegating to external agent...]</i>")
                    delegateToCli(userPrompt, onProgress, ::log, onComplete)
                    return@launch
                }

                // If both task modes are disabled, bypass Gandalf entirely and go to Galadriel
                val routeDecision: RouteDecision = if (!easyEnabled && !complexEnabled) {
                    onProgress("<i>[⚙ Task modes disabled — going directly to Galadriel]</i>")
                    RouteDecision.CHAT
                } else {
                    val routeResult = route(userPrompt, conversationHistory)
                    log("[🧙 Gandalf (${routeResult.provider})] → ${routeResult.raw}")
                    onProgress("<i>[🧙 Gandalf (${routeResult.provider}): ${routeResult.raw}]</i>")
                    when {
                        routeResult.decision == RouteDecision.EASY_TASK && !easyEnabled -> RouteDecision.CHAT
                        routeResult.decision == RouteDecision.COMPLEX_TASK && !complexEnabled -> RouteDecision.CHAT
                        else -> routeResult.decision
                    }
                }

                if (routeDecision == RouteDecision.CHAT) {
                    // If Galadriel is CLI, delegate chat to CLI
                    if (isCli("Galadriel")) {
                        onProgress("<i>[🧝 Galadriel (CLI): delegating to external agent...]</i>")
                        delegateToCli(userPrompt, onProgress, ::log, onComplete)
                        return@launch
                    }
                    val contextPrompt = buildString {
                        if (projectSkeleton.isNotEmpty()) append("Project context:\n$projectSkeleton\n\n")
                        if (conversationHistory.isNotEmpty()) {
                            append("Conversation so far:\n")
                            conversationHistory.forEach { (role, content) -> append("$role: $content\n") }
                            append("\n")
                        }
                        append("User: $userPrompt")
                    }
                    val galadrielPersona = promptFor("Galadriel", "galadriel_system_prompt.txt")
                    val galadrielResp = llmService.askCharacterWithMeta("Galadriel", contextPrompt, galadrielPersona)
                    onStats?.invoke(1, 0)
                    log("[🧝 Galadriel (${galadrielResp.provider})] ${galadrielResp.text}")
                    onProgress("<b>[🧝 Galadriel (${galadrielResp.provider})]</b> ${PlanParser.markdownToHtml(galadrielResp.text)}")
                    return@launch
                }

                // For EASY_TASK / COMPLEX_TASK: if the next character in the chain is CLI, delegate
                if (routeDecision == RouteDecision.EASY_TASK && isCli("Aragorn")) {
                    onProgress("<i>[⚔️ Aragorn (CLI): delegating to external agent...]</i>")
                    delegateToCli(userPrompt, onProgress, ::log, onComplete)
                    return@launch
                }
                if (routeDecision == RouteDecision.COMPLEX_TASK && isCli("Elrond")) {
                    onProgress("<i>[🧙 Elrond (CLI): delegating to external agent...]</i>")
                    delegateToCli(userPrompt, onProgress, ::log, onComplete)
                    return@launch
                }

                if (routeDecision == RouteDecision.EASY_TASK) {
                    val aragornPersona = promptFor("Aragorn", "aragorn_system_prompt.txt")
                        .replace("{{TOOLS}}", toolRegistry.toJsonSchema())
                    val aragornPrompt = "QUEST (this is what the user wants — focus on this):\n$userPrompt\n\nBACKGROUND CONTEXT (existing files in the project — do NOT reuse these paths for new files):\n$projectSkeleton"
                    val aragornResp = llmService.askCharacterWithMeta("Aragorn", aragornPrompt, aragornPersona, "{")
                    val aragornJson = aragornResp.text
                    onStats?.invoke(1, 0)
                    log("[⚔️ Aragorn (${aragornResp.provider})] $aragornJson")
                    onProgress("<i>[⚔️ Aragorn (${aragornResp.provider}): preparing the task...]</i>")
                    if (isSystemError(aragornJson)) {
                        onProgress("<i>[⚔️ Aragorn: local model unavailable, escalating to Elrond...]</i>")
                    } else {
                        val aragornTasks = PlanParser.parseAragornResult(aragornJson, userPrompt)
                        if (!aragornTasks.isNullOrEmpty()) {
                        onProgress("<i>[🌿 Samwise: executing ${aragornTasks.size} task(s)...]</i>")
                        val sessionId = shadowLog.startSession()
                        log("[Shadow Log] Session opened: $sessionId")
                        val shadowedFileOp = ShadowedFileOperator(fileOperator, shadowLog)
                        val allSuccess = try {
                            executeTasks(aragornTasks, userPrompt, projectSkeleton, shadowedFileOp, onProgress, ::log)
                        } finally {
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
                            return@launch
                        }
                        onProgress("<i>[🔄 Aragorn failed, escalating to Elrond & Celebrimbor...]</i>")
                        }
                    } // end else (not system error)
                } // end if EASY_TASK

                // Palantír: load the semantic index and query for the most relevant files
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

                val elrondPersona = promptFor("Elrond", "elrond_system_prompt.txt")
                val elrondPrompt = buildString {
                    if (conversationHistory.isNotEmpty()) {
                        append("Conversation history:\n")
                        conversationHistory.forEach { (role, c) -> append("$role: ${PlanParser.cleanForHistory(c)}\n") }
                        append("\n")
                    }
                    append("QUEST (this is what the user wants — focus on this):\n$userPrompt\n\n")
                    if (relevantContext == projectSkeleton) {
                        append("BACKGROUND CONTEXT (existing files — do NOT reuse these paths for new files):\n$projectSkeleton")
                    } else {
                        append("RELEVANT FILES (by semantic relevance — background context only):\n$relevantContext")
                    }
                }
                val elrondResp = llmService.askCharacterWithMeta("Elrond", elrondPrompt, elrondPersona, "{")
                onStats?.invoke(1, 0)
                log("[🧙 Elrond (${elrondResp.provider})] ${elrondResp.text}")
                onProgress("<i>[🧙 Elrond (${elrondResp.provider}): preparing the brief...]</i>")

                val celebrimborPersona = promptFor("Celebrimbor", "celebrimbor_system_prompt.txt")
                    .replace("{{TOOLS}}", toolRegistry.toJsonSchema())
                val celebrimborPrompt = buildString {
                    val elrondBrief = PlanParser.sanitizeJson(elrondResp.text).take(2000)
                    append("Brief from Elrond:\n$elrondBrief\n\n")
                    append("BACKGROUND CONTEXT (existing files — do NOT reuse these paths for new files):\n$projectSkeleton")
                }
                val celebrimborResp = llmService.askCharacterWithMeta("Celebrimbor", celebrimborPrompt, celebrimborPersona)
                val celebrimborJson = celebrimborResp.text
                onStats?.invoke(0, 1)
                log("[💎 Celebrimbor (${celebrimborResp.provider})] $celebrimborJson")
                onProgress("<i>[💎 Celebrimbor (${celebrimborResp.provider}): forging the plan...]</i>")
                val planResult = PlanParser.parsePlannerResult(celebrimborJson, userPrompt)

                when (planResult.strategy) {
                    "direct" -> {
                        onProgress("<b>Celebrimbot:</b> ${planResult.response}")
                    }
                    "plan" -> {
                        val tasks = planResult.tasks
                        if (tasks.isEmpty()) {
                            onProgress("<b>Celebrimbot:</b> Could not generate a valid plan. Raw: <code>${celebrimborJson.take(300)}</code>")
                            return@launch
                        }
                        onProgress("<i>[🔄 Workers: executing ${tasks.size} task(s)...]</i>")
                        val sessionId = shadowLog.startSession()
                        log("[Shadow Log] Session opened: $sessionId")
                        val shadowedFileOp = ShadowedFileOperator(fileOperator, shadowLog)
                        try {
                            executeTasks(tasks, userPrompt, projectSkeleton, shadowedFileOp, onProgress, ::log)
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
        val summary = buildString {
            append("User asked: $userPrompt\n")
            tasks.filter { it.action == "read_psi" }.joinToString(", ") { it.target ?: "" }.let { if (it.isNotEmpty()) append("Files read: $it\n") }
            tasks.filter { it.action == "write_code" }.joinToString(", ") { it.target ?: "" }.let { if (it.isNotEmpty()) append("Files written: $it\n") }
            tasks.filter { it.action == "delete_file" }.joinToString(", ") { it.target ?: "" }.let { if (it.isNotEmpty()) append("Files deleted: $it\n") }
            tasks.filter { it.action == "run_terminal" }.joinToString(", ") { it.command ?: "" }.let { if (it.isNotEmpty()) append("Commands run: $it\n") }
        }
        val bilboPersona = promptFor("Bilbo", "bilbo_system_prompt.txt")
        val bilboResp = llmService.askCharacterWithMeta("Bilbo", summary, bilboPersona)
        onProgress("<b>[📖 Bilbo (${bilboResp.provider})]</b> ${PlanParser.markdownToHtml(bilboResp.text)}")
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
                val elrondBrief = PlanParser.sanitizeJson(additionalPrompt).take(2000)
                append("Brief from Elrond:\n$elrondBrief\n\n")
                append("BACKGROUND CONTEXT (existing files — do NOT reuse these paths for new files):\n$projectSkeleton")
            }
            val celebrimborPersonaT = promptFor("Celebrimbor", "celebrimbor_system_prompt.txt")
                .replace("{{TOOLS}}", toolRegistry.toJsonSchema())
            val celebrimborRespT = llmService.askCharacterWithMeta("Celebrimbor", celebrimborPrompt, celebrimborPersonaT)
            val celebrimborJson = celebrimborRespT.text
            onStats?.invoke(0, 1)
            log("[Treebeard re-plan → Celebrimbor (${celebrimborRespT.provider})] $celebrimborJson")

            val planResult = PlanParser.parsePlannerResult(celebrimborJson, userPrompt)
            if (planResult.strategy != "plan" || planResult.tasks.isEmpty()) {
                onProgress("<i>[🌳 Treebeard: Celebrimbor could not forge a new plan. Proceeding to Bilbo.]</i>")
                break
            }

            val newTasks = planResult.tasks
            onProgress("<i>[🔄 Workers: executing ${newTasks.size} additional task(s) (Treebeard cycle ${reflectionLoop + 1})]</i>")
            val sessionId = shadowLog.startSession()
            log("[Shadow Log] Treebeard session opened: $sessionId")
            try {
                executeTasks(newTasks, userPrompt, projectSkeleton, shadowedFileOp as ShadowedFileOperator, onProgress, log)
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
        if (written.isNotEmpty()) {
            append("Files written:\n")
            written.forEach { task ->
                val content = task.target?.let { runCatching { fileOperator.readFile(it) }.getOrNull() } ?: ""
                append("  - ${task.target} (${content.length} chars)\n")
                if (content.isNotEmpty()) append("    Preview: ${content.take(500)}\n")
            }
        }
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
        val samwiseProvider = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
            .getInstance(project).getAgentConfig("Samwise").provider.displayName
        while (retryCount < 3) {
            val workerLabel = when (task.action) {
                "write_code" -> null // write_code shows its own label inside writeCodeAction
                else -> "🌿 Samwise ($samwiseProvider)"
            }
            if (workerLabel != null)
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
        val escalationResp = llmService.askCharacterWithMeta("Elrond", escalationPrompt, promptFor("Elrond", "elrond_system_prompt.txt"), "{")
        val escalationResult = PlanParser.parsePlannerResult(escalationResp.text)
        if (escalationResult.strategy == "direct" && escalationResult.response.isNotEmpty()) {
            onProgress("<b>Celebrimbot (Elrond fallback, ${escalationResp.provider}):</b> ${escalationResult.response}")
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
        // Preserve absolute paths (e.g. /Users/...). Only strip a leading slash from
        // relative paths that accidentally got one prepended by the planner.
        val rawTarget = task.target
        val t = task.copy(target = if (rawTarget != null && rawTarget.length > 1 && rawTarget.startsWith("/") && !rawTarget.startsWith("/Users") && !rawTarget.startsWith("/home") && !rawTarget.startsWith("/tmp")) rawTarget.trimStart('/') else rawTarget)

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

        val workerCharacter = if (useLegolasGimli) "LegolasGimli" else "Frodo"
        val workerPersona = promptFor(workerCharacter,
            if (useLegolasGimli) "legolas_gimli_system_prompt.txt" else "frodo_system_prompt.txt"
        )
        fun askWorker(prompt: String): Pair<String, String> {
            val resp = llmService.askCharacterWithMeta(workerCharacter, prompt, workerPersona)
            return Pair(resp.text, resp.provider)
        }

        // ── Initial write ────────────────────────────────────────────────────
        val (firstResponse, firstProvider) = askWorker(buildWorkerPrompt("", ""))
        val workerName = if (useLegolasGimli) "🏹 Legolas & Gimli" else "🧙 Frodo"
        onProgress("<i>[$workerName ($firstProvider): writing $targetPath]</i>")
        val firstCode = PlanParser.extractCode(firstResponse)
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

            val (fixResponse, fixProvider) = askWorker(buildWorkerPrompt(lastOutput, brokenContent))
            onProgress("<i>[$workerName ($fixProvider): fixing $targetPath (cycle $cycleLabel)]</i>")
            val fixedCode = PlanParser.extractCode(fixResponse) ?: run {
                // Worker returned no code block — keep the last output as the error
                return@repeat
            }
            effectiveFileOp.writeFile(targetPath, fixedCode)
        }

        // All cycles exhausted
        return ActionResult(false, "Council's Review: build still failing after $MAX_VALIDATION_CYCLES cycles.\n$lastOutput")
    }

    private fun executeTasks(
        tasks: List<CelebrimbotTask>,
        userPrompt: String,
        projectSkeleton: String,
        shadowedFileOp: ShadowedFileOperator,
        onProgress: (String) -> Unit,
        log: (String) -> Unit
    ): Boolean {
        // Map of path -> content for files read during this session
        val readContext = mutableMapOf<String, String>()
        var allSuccess = true
        for (task in tasks) {
            // Pass the content of the file being written if it was previously read
            val contextForTask = task.target?.let { readContext[it] } ?: ""
            val result = executeTaskWithRetry(task, onProgress, contextForTask, userPrompt, projectSkeleton, shadowedFileOp)
            if (task.action == "read_psi" && result.isSuccess) {
                readContext[task.target ?: ""] = result.output
                onProgress("<i>[📄 Read ${task.target}: ${result.output.length} chars]</i>")
            } else if (result.isSuccess && task.action in listOf(
                    "web_search", "fetch_page", "list_files", "grep_files",
                    "find_file", "file_stats", "git_status", "git_log",
                    "git_diff", "git_blame", "git_branch"
                )) {
                onProgress("<b>Celebrimbot:</b> <pre>${PlanParser.escapeHtml(result.output)}</pre>")
            }
            if (!result.isSuccess) { allSuccess = false; break }
        }
        return allSuccess
    }

    companion object {
        private const val MAX_VALIDATION_CYCLES = 3
        private const val MAX_REFLECTION_LOOPS = 2
        fun getInstance(project: Project): CelebrimbotAgentOrchestrator = project.service()
    }



    private fun promptFor(character: String, filename: String): String {
        val settings = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
            .getInstance(project)
        // Use custom prompt if the user has overridden it in settings
        val custom = settings.state.customPrompts[character]
        if (!custom.isNullOrBlank()) return custom
        val provider = settings.getAgentConfig(character).provider
        return CelebrimbotLlmService.loadPromptForProvider(filename, provider)
    }

    private enum class RouteDecision { CHAT, EASY_TASK, COMPLEX_TASK }

    private data class RouteResult(val decision: RouteDecision, val raw: String, val provider: String)

    private fun route(prompt: String, history: List<Pair<String, String>>): RouteResult {
        val historyContext = if (history.isNotEmpty())
            "Conversation history:\n" + history.takeLast(6).joinToString("\n") { (r, c) -> "$r: $c" } + "\n\n"
        else ""
        val gandalfPrompt = historyContext + "Current request: " + prompt
        val gandalfPersona = promptFor("Gandalf", "gandalf_system_prompt.txt")
        val response = llmService.askCharacterWithMeta("Gandalf", gandalfPrompt, gandalfPersona)
        val raw = response.text.trim().uppercase()
        val decision = when {
            raw.startsWith("COMPLEX") -> RouteDecision.COMPLEX_TASK
            raw.startsWith("EASY") -> RouteDecision.EASY_TASK
            raw.startsWith("CHAT") -> RouteDecision.CHAT
            else -> RouteDecision.COMPLEX_TASK
        }
        return RouteResult(decision, response.text.trim(), response.provider)
    }

    private fun isSystemError(s: String) = s.startsWith("{System:") || s.startsWith("System:") || s.contains("too slow or stuck")



    data class ActionResult(val isSuccess: Boolean, val output: String)

    /** Escapes a string for safe use as a shell argument. */
    private fun shellEscape(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Delegates a prompt to the external CLI and streams output back to chat. */
    private fun delegateToCli(
        prompt: String,
        onProgress: (String) -> Unit,
        log: (String) -> Unit,
        onComplete: (() -> Unit)?
    ): Boolean {
        val cliCmd = com.github.giacomosaccaggi.celebrimbot.settings.CelebrimbotSettingsState
            .getInstance(project).state.cliCommand.trim()
        if (cliCmd.isEmpty()) {
            onProgress("<b>Error:</b> Character is set to CLI but no CLI command is configured in settings.")
            onComplete?.invoke()
            return true
        }
        // If the command contains {{MESSAGE}}, substitute it; otherwise append as argument
        val fullCmd = if (cliCmd.contains("{{MESSAGE}}")) {
            cliCmd.replace("{{MESSAGE}}", shellEscape(prompt))
        } else {
            "$cliCmd ${shellEscape(prompt)}"
        }
        log("[CLI] Delegating to: $fullCmd")
        onProgress("<i>[⚙ Delegating to CLI: <code>${com.github.giacomosaccaggi.celebrimbot.parser.PlanParser.escapeHtml(cliCmd)}</code>]</i>")
        val outputLines = mutableListOf<String>()
        var responseShown = false
        CelebrimbotTerminalService.getInstance(project).runCliAndStream(
            command = fullCmd,
            onOutput = { line ->
                val stripped = line.replace(Regex("\u001B\\[[^A-Za-z]*[A-Za-z]"), "")
                    .replace(Regex("\u001B\\[\\?[0-9]+[hl]"), "")
                    .replace("\u0007", "")
                    .trim()
                if (stripped.isEmpty()) return@runCliAndStream

                // Detect end of response and show final result
                if (stripped.contains("▸ Time:") || stripped.startsWith("▸ Time:")) {
                    if (!responseShown && outputLines.isNotEmpty()) {
                        responseShown = true
                        val lastActionIdx = outputLines.indexOfLast { l ->
                            l.startsWith("Creating:") || l.startsWith("Replacing:")
                                || l.startsWith("- Completed") || l.startsWith("✓ Successfully")
                        }
                        val finalLines = if (lastActionIdx >= 0 && lastActionIdx < outputLines.size - 1) {
                            outputLines.subList(lastActionIdx + 1, outputLines.size)
                        } else {
                            outputLines.toList()
                        }
                        val finalResponse = finalLines.joinToString("\n")
                        ApplicationManager.getApplication().invokeLater {
                            onProgress("<b>CLI:</b> ${PlanParser.markdownToHtml(finalResponse)}")
                            onComplete?.invoke()
                        }
                    }
                    return@runCliAndStream
                }

                // Filter noise
                if (stripped.contains("Thinking...") && !stripped.contains("> ")) return@runCliAndStream
                if (stripped.contains("mcp servers initialized")) return@runCliAndStream
                if (stripped.contains("loaded in ") && ("✓" in stripped || "✗" in stripped)) return@runCliAndStream
                if (stripped.contains("has failed to load")) return@runCliAndStream
                if (stripped.contains("Mcp error:")) return@runCliAndStream
                if (stripped.contains("KIRO_LOG_LEVEL")) return@runCliAndStream
                if (stripped.contains("ctrl-c")) return@runCliAndStream
                if (stripped.contains("Did you know?")) return@runCliAndStream
                if (stripped.startsWith("Picking up")) return@runCliAndStream
                if (stripped.startsWith("Model:") && stripped.contains("/model")) return@runCliAndStream
                if (stripped.startsWith("All tools are now trusted")) return@runCliAndStream
                if (stripped.startsWith("Agents can sometimes")) return@runCliAndStream
                if (stripped.startsWith("Learn more at")) return@runCliAndStream
                if (stripped.startsWith("Authenticated")) return@runCliAndStream
                if (stripped.startsWith("Error: Stream closed")) return@runCliAndStream
                if (stripped.all { it.code > 0x2800 || it.isWhitespace() || it == '7' || it == '8' }) return@runCliAndStream
                if (stripped.length > 20 && stripped.count { it.code > 0x2500 } > stripped.length / 2) return@runCliAndStream
                if (stripped.all { it in "╭╮╰╯│─ " || it.isWhitespace() }) return@runCliAndStream
                if (stripped.startsWith("│") && stripped.endsWith("│") && stripped.length > 40) return@runCliAndStream

                // Extract meaningful text
                val text = if (stripped.contains("Thinking...") && stripped.contains("> ")) {
                    stripped.substringAfterLast("> ").trim()
                } else {
                    stripped.replace(Regex("^\\+\\s*\\d+:"), "").trim()
                }
                    .replace(Regex("[⠀-⣿]+"), "")
                    .replace(Regex("✗ \\S+ has failed to load after [\\d.]+ s"), "")
                    .trim()

                if (text.isNotEmpty()) {
                    outputLines.add(text)
                    log("[CLI] $text")
                }
            },
            onDone = {
                // Fallback: if process ended without ▸ Time: marker
                if (!responseShown && outputLines.isNotEmpty()) {
                    responseShown = true
                    ApplicationManager.getApplication().invokeLater {
                        val finalResponse = outputLines.takeLast(5).joinToString("\n")
                        onProgress("<b>CLI:</b> ${PlanParser.markdownToHtml(finalResponse)}")
                        onComplete?.invoke()
                    }
                } else if (!responseShown) {
                    ApplicationManager.getApplication().invokeLater { onComplete?.invoke() }
                }
            }
        )
        return true
    }
}
