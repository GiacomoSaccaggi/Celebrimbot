package com.github.giacomosaccaggi.celebrimbot.eval

import com.github.giacomosaccaggi.celebrimbot.io.*
import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTask
import com.github.giacomosaccaggi.celebrimbot.parser.PlanParser
import com.github.giacomosaccaggi.celebrimbot.registry.ToolRegistry
import com.github.giacomosaccaggi.celebrimbot.registry.tools.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

// ── Data classes ──────────────────────────────────────────────────────────────

data class EvalCase(
    val id: String,
    val description: String,
    val input: String,
    val expectedRoute: String,
    val judgeCriteria: String
)

data class EvalResult(
    val id: String,
    val description: String,
    val input: String,
    val expectedRoute: String,
    val actualRoute: String,
    val routeCorrect: Boolean,
    val agentTrace: List<String>,
    val internalLog: List<String>,
    val filesCreated: List<String>,
    val judgeVerdict: JudgeVerdict,
    val durationMs: Long
)

data class JudgeVerdict(
    val passed: Boolean,
    val score: Int,
    val reasoning: String,
    val issues: List<String>
)

// ── LLM provider interface ────────────────────────────────────────────────────

interface HeadlessLlmProvider {
    fun ask(character: String, prompt: String, persona: String, jsonPrefix: String = ""): String
}

// ── Amazon Q headless provider ────────────────────────────────────────────────

class AmazonQHeadlessProvider : HeadlessLlmProvider {

    private val gson = Gson()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun ask(character: String, prompt: String, persona: String, jsonPrefix: String): String {
        val token = readSsoToken() ?: error("Amazon Q not authenticated — run 'aws sso login'")
        val body = gson.toJson(mapOf(
            "conversationState" to mapOf(
                "conversationId" to java.util.UUID.randomUUID().toString(),
                "chatTriggerType" to "MANUAL",
                "currentMessage" to mapOf(
                    "userInputMessage" to mapOf(
                        "content" to "$persona\n\n$prompt",
                        "userInputMessageContext" to emptyMap<String, Any>()
                    )
                )
            )
        ))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://codewhisperer.us-east-1.amazonaws.com/"))
            .header("Content-Type", "application/x-amz-json-1.0")
            .header("X-Amz-Target", "AmazonCodeWhispererStreamingService.GenerateAssistantResponse")
            .header("Authorization", "Bearer $token")
            .timeout(Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) error("Amazon Q HTTP ${response.statusCode()}: ${response.body().take(200)}")
        return parseResponse(response.body())
    }

    private fun parseResponse(raw: String): String {
        val chunks = mutableListOf<String>()
        Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(raw).forEach { match ->
            chunks.add(match.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t"))
        }
        return chunks.joinToString("").ifBlank { error("unexpected Amazon Q response format") }
    }

    private fun readSsoToken(): String? {
        val cacheDir = File(System.getProperty("user.home"), ".aws/sso/cache")
        if (!cacheDir.isDirectory) return null
        return cacheDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                runCatching {
                    val obj = gson.fromJson(file.readText(), JsonObject::class.java) ?: return@runCatching null
                    val accessToken = obj.get("accessToken")?.asString ?: return@runCatching null
                    val expiry = Instant.parse(obj.get("expiresAt")?.asString ?: return@runCatching null)
                    if (expiry.isBefore(Instant.now().plusSeconds(60))) return@runCatching null
                    Pair(accessToken, expiry)
                }.getOrNull()
            }
            ?.maxByOrNull { it.second }
            ?.first
    }
}

// ── Headless agent pipeline ───────────────────────────────────────────────────

class HeadlessAgentPipeline(
    private val workDir: File,
    private val llmProvider: HeadlessLlmProvider,
    private val onProgress: (String) -> Unit,
    private val onInternalLog: (String) -> Unit
) {
    private val fileOp = HeadlessFileOperator(workDir.absolutePath)
    private val shadowLog = HeadlessShadowLogOperator(workDir.absolutePath)
    private val toolRegistry = ToolRegistry().apply {
        register(ReadFileTool(fileOp))
        register(WriteFileTool(fileOp))
        register(DeleteFileTool(fileOp))
        register(RunTerminalTool(HeadlessTerminalOperator(workDir.absolutePath)))
        register(WebSearchTool(DuckDuckGoSearchOperator()))
        register(FetchPageTool(DuckDuckGoSearchOperator()))
        register(ListFilesTool(HeadlessProjectScanOperator(workDir.absolutePath)))
        register(GrepFilesTool(HeadlessProjectScanOperator(workDir.absolutePath)))
        register(FindFileTool(HeadlessProjectScanOperator(workDir.absolutePath)))
        register(FileStatsTool(HeadlessProjectScanOperator(workDir.absolutePath)))
        register(GitStatusTool(HeadlessGitOperator(workDir.absolutePath)))
        register(GitLogTool(HeadlessGitOperator(workDir.absolutePath)))
        register(GitDiffTool(HeadlessGitOperator(workDir.absolutePath)))
        register(GitBlameTool(HeadlessGitOperator(workDir.absolutePath)))
        register(GitBranchTool(HeadlessGitOperator(workDir.absolutePath)))
    }

    fun execute(userPrompt: String) {
        val skeleton = buildSkeleton()
        val gandalfPersona = loadPrompt("gandalf_system_prompt.txt")
        val routeRaw = llmProvider.ask("Gandalf", userPrompt, gandalfPersona).trim().uppercase()
        onInternalLog("[Gandalf] → $routeRaw")
        onProgress("[🧙 Gandalf: $routeRaw]")

        when {
            routeRaw.startsWith("CHAT") -> handleChat(userPrompt, skeleton)
            routeRaw.startsWith("EASY") -> handleEasyTask(userPrompt, skeleton)
            else -> handleComplexTask(userPrompt, skeleton)
        }
    }

    private fun handleChat(userPrompt: String, skeleton: String) {
        val persona = loadPrompt("galadriel_system_prompt.txt")
        val response = llmProvider.ask("Galadriel", "User: $userPrompt", persona)
        onInternalLog("[Galadriel] $response")
        onProgress("[🧝 Galadriel] $response")
    }

    private fun handleEasyTask(userPrompt: String, skeleton: String) {
        val persona = loadPrompt("aragorn_system_prompt.txt").replace("{{TOOLS}}", toolRegistry.toJsonSchema())
        val json = llmProvider.ask("Aragorn", "QUEST:\n$userPrompt\n\nBACKGROUND:\n$skeleton", persona, jsonPrefix = "{")
        onInternalLog("[Aragorn] $json")
        onProgress("[⚔️ Aragorn: preparing the task...]")
        val tasks = PlanParser.parseAragornResult(json, userPrompt) ?: return
        executeTasks(tasks, userPrompt)
    }

    private fun handleComplexTask(userPrompt: String, skeleton: String) {
        val elrondPersona = loadPrompt("elrond_system_prompt.txt")
        val elrondJson = llmProvider.ask("Elrond", "QUEST:\n$userPrompt\n\nBACKGROUND:\n$skeleton", elrondPersona, jsonPrefix = "{")
        onInternalLog("[Elrond] $elrondJson")
        onProgress("[🧙 Elrond: preparing the brief...]")

        val celebrimborPersona = loadPrompt("celebrimbor_system_prompt.txt").replace("{{TOOLS}}", toolRegistry.toJsonSchema())
        val celebrimborJson = llmProvider.ask("Celebrimbor", "Brief from Elrond:\n${elrondJson.take(2000)}\n\nBACKGROUND:\n$skeleton", celebrimborPersona)
        onInternalLog("[Celebrimbor] $celebrimborJson")
        onProgress("[💎 Celebrimbor: forging the plan...]")

        val plan = PlanParser.parsePlannerResult(celebrimborJson, userPrompt)
        if (plan.strategy != "plan" || plan.tasks.isEmpty()) return
        executeTasks(plan.tasks, userPrompt)
    }

    private fun executeTasks(tasks: List<CelebrimbotTask>, userPrompt: String) {
        val readContext = mutableMapOf<String, String>()
        val shadowedFileOp = ShadowedFileOperator(fileOp, shadowLog)
        shadowLog.startSession()
        try {
            for (task in tasks) {
                val context = task.target?.let { readContext[it] } ?: ""
                when (task.action) {
                    "write_code" -> {
                        val isLegolas = task.worker == "legolas_gimli"
                        val persona = loadPrompt(if (isLegolas) "legolas_gimli_system_prompt.txt" else "frodo_system_prompt.txt")
                        val character = if (isLegolas) "LegolasGimli" else "Frodo"
                        val workerPrompt = "Task: \"${task.instruction}\"\nTarget: ${task.target}\n${if (context.isNotEmpty()) "Current content:\n$context" else ""}"
                        val response = llmProvider.ask(character, workerPrompt, persona)
                        val code = PlanParser.extractCode(response) ?: response
                        shadowedFileOp.writeFile(task.target ?: continue, code)
                        onProgress("✅ Code written to ${task.target}")
                    }
                    "read_psi" -> {
                        val content = fileOp.readFile(task.target ?: continue)
                        readContext[task.target ?: ""] = content
                        onProgress("[📄 Read ${task.target}: ${content.length} chars]")
                    }
                    "delete_file" -> {
                        fileOp.deleteFile(task.target ?: continue)
                        onProgress("✅ Deleted ${task.target}")
                    }
                    else -> {
                        val tool = toolRegistry.get(task.action) ?: continue
                        val args = buildMap<String, String> {
                            task.target?.let { put("target", it) }
                            task.command?.let { put("command", it) }
                            task.query?.let { put("query", it) }
                        }
                        val result = tool.execute(args)
                        if (result.success) onProgress(result.output.take(500))
                    }
                }
            }
        } finally {
            shadowLog.endSession()
        }

        // Treebeard review
        val treebeardPersona = loadPrompt("treebeard_system_prompt.txt")
        val writtenSummary = tasks.filter { it.action == "write_code" }.joinToString("\n") { task ->
            val content = task.target?.let { runCatching { fileOp.readFile(it) }.getOrNull() } ?: ""
            "  - ${task.target} (${content.length} chars)\n    Preview: ${content.take(300)}"
        }
        val verdictRaw = llmProvider.ask("Treebeard",
            "ORIGINAL QUEST:\n$userPrompt\n\nFILES WRITTEN:\n$writtenSummary\n\nDeliver your verdict as JSON.",
            treebeardPersona, jsonPrefix = "{")
        onInternalLog("[Treebeard] $verdictRaw")

        // Bilbo
        val bilboPersona = loadPrompt("bilbo_system_prompt.txt")
        val bilboSummary = "User asked: $userPrompt\nFiles written: ${tasks.filter { it.action == "write_code" }.mapNotNull { it.target }.joinToString(", ")}"
        val bilboResponse = llmProvider.ask("Bilbo", bilboSummary, bilboPersona)
        onInternalLog("[Bilbo] $bilboResponse")
        onProgress("[📖 Bilbo] $bilboResponse")
    }

    private fun buildSkeleton(): String = buildString {
        append("Project Structure:\n")
        workDir.walkTopDown().filter { it.isFile }.take(50).forEach {
            append("- ${it.relativeTo(workDir).path}\n")
        }
    }

    private fun loadPrompt(filename: String): String =
        HeadlessAgentPipeline::class.java.getResourceAsStream("/prompts/$filename")
            ?.bufferedReader()?.readText()?.trim()
            ?: error("Prompt not found: $filename")
}

// ── LLM-as-a-Judge ────────────────────────────────────────────────────────────

class AgentJudge(private val llmProvider: HeadlessLlmProvider) {

    private val gson = Gson()

    fun evaluate(evalCase: EvalCase, actualRoute: String, agentTrace: List<String>, internalLog: List<String>, filesCreated: List<String>, workDir: File): JudgeVerdict {
        val fileContents = filesCreated.take(5).joinToString("\n\n") { path ->
            "=== $path ===\n${runCatching { File(workDir, path).readText() }.getOrDefault("").take(1000)}"
        }
        val judgePrompt = """
You are an expert evaluator for an AI coding agent system called Celebrimbot.

TEST CASE:
- Input: "${evalCase.input}"
- Expected route: ${evalCase.expectedRoute}
- Actual route: $actualRoute
- Judge criteria: ${evalCase.judgeCriteria}

AGENT TRACE:
${agentTrace.joinToString("\n")}

INTERNAL LOG (last 20 lines):
${internalLog.takeLast(20).joinToString("\n")}

FILES CREATED: ${if (filesCreated.isEmpty()) "none" else filesCreated.joinToString(", ")}

FILE CONTENTS:
$fileContents

Respond with ONLY this JSON (no markdown):
{"passed":true/false,"score":0-10,"reasoning":"brief explanation","issues":["issue1"]}
""".trimIndent()

        return try {
            val raw = llmProvider.ask("Judge", judgePrompt, "You are a strict evaluator. Respond with valid JSON only.")
            val jsonStr = Regex("""\{[\s\S]*}""").find(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())?.value ?: raw
            val obj = gson.fromJson(jsonStr, JsonObject::class.java)
            val issuesType = object : TypeToken<List<String>>() {}.type
            JudgeVerdict(
                passed = obj.get("passed")?.asBoolean ?: false,
                score = obj.get("score")?.asInt ?: 0,
                reasoning = obj.get("reasoning")?.asString ?: "",
                issues = runCatching { gson.fromJson<List<String>>(obj.getAsJsonArray("issues"), issuesType) }.getOrDefault(emptyList())
            )
        } catch (e: Exception) {
            JudgeVerdict(false, 0, "Judge failed: ${e.message}", listOf("Could not parse judge response"))
        }
    }
}

// ── Eval runner ───────────────────────────────────────────────────────────────

class EvalRunner(private val llmProvider: HeadlessLlmProvider) {

    private val gson = Gson()
    private val judge = AgentJudge(llmProvider)

    fun runSuite(suiteFile: File, outputDir: File): List<EvalResult> {
        val type = object : TypeToken<List<EvalCase>>() {}.type
        val cases: List<EvalCase> = gson.fromJson(suiteFile.readText(), type)
        outputDir.mkdirs()
        val results = mutableListOf<EvalResult>()

        cases.forEach { evalCase ->
            println("\n[Eval] Running: ${evalCase.id}")
            val workDir = File(outputDir, "workdir_${evalCase.id}").also { it.mkdirs() }
            val trace = mutableListOf<String>()
            val logs = mutableListOf<String>()

            val start = System.currentTimeMillis()
            runCatching {
                HeadlessAgentPipeline(workDir, llmProvider, { trace.add(it) }, { logs.add(it) }).execute(evalCase.input)
            }.onFailure { e ->
                logs.add("[ERROR] ${e.message}")
                trace.add("Pipeline error: ${e.message}")
            }
            val duration = System.currentTimeMillis() - start

            val files = workDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(workDir).path }.toList()
            val actualRoute = logs.firstOrNull { it.contains("[Gandalf]") }
                ?.let { Regex("→\\s*(\\w+)").find(it)?.groupValues?.get(1)?.uppercase() } ?: "UNKNOWN"

            val verdict = judge.evaluate(evalCase, actualRoute, trace, logs, files, workDir)
            results.add(EvalResult(evalCase.id, evalCase.description, evalCase.input, evalCase.expectedRoute, actualRoute,
                actualRoute.startsWith(evalCase.expectedRoute), trace, logs, files, verdict, duration))

            println("${if (verdict.passed) "✅" else "❌"} ${evalCase.id}: score=${verdict.score}/10, route=$actualRoute (expected ${evalCase.expectedRoute})")
            if (verdict.issues.isNotEmpty()) println("   Issues: ${verdict.issues.joinToString("; ")}")
        }

        writeReport(results, outputDir)
        return results
    }

    private fun writeReport(results: List<EvalResult>, outputDir: File) {
        File(outputDir, "eval_report.json").writeText(gson.toJson(results))

        val passed = results.count { it.judgeVerdict.passed }
        val total = results.size
        val avgScore = if (total > 0) results.sumOf { it.judgeVerdict.score }.toDouble() / total else 0.0

        File(outputDir, "eval_report.md").writeText(buildString {
            appendLine("# Celebrimbot Eval Report")
            appendLine("Generated: ${java.time.LocalDateTime.now()}")
            appendLine()
            appendLine("## Summary")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Total | $total |")
            appendLine("| Passed | $passed |")
            appendLine("| Failed | ${total - passed} |")
            appendLine("| Pass rate | ${"%.1f".format(passed.toDouble() / total * 100)}% |")
            appendLine("| Avg score | ${"%.1f".format(avgScore)}/10 |")
            appendLine()
            appendLine("## Results")
            results.forEach { r ->
                appendLine("### ${if (r.judgeVerdict.passed) "✅" else "❌"} ${r.id}")
                appendLine("**Input:** `${r.input}`")
                appendLine("**Route:** expected `${r.expectedRoute}` → actual `${r.actualRoute}` ${if (r.routeCorrect) "✅" else "❌"}")
                appendLine("**Score:** ${r.judgeVerdict.score}/10 | **Duration:** ${r.durationMs}ms")
                appendLine("**Reasoning:** ${r.judgeVerdict.reasoning}")
                if (r.judgeVerdict.issues.isNotEmpty()) r.judgeVerdict.issues.forEach { appendLine("- $it") }
                if (r.filesCreated.isNotEmpty()) appendLine("**Files:** ${r.filesCreated.joinToString(", ")}")
                appendLine()
            }
        })
        println("\n[Eval] Report → ${outputDir.absolutePath}/eval_report.md")
    }
}
