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

@Service(Service.Level.PROJECT)
class CelebrimbotAgentOrchestrator(private val project: Project) {

    private val gson = Gson()
    private val llmService = CelebrimbotLlmService.getInstance(project)
    private val terminalService = CelebrimbotTerminalService.getInstance(project)

    fun executePlan(
        userPrompt: String,
        projectSkeleton: String,
        conversationHistory: List<Pair<String, String>> = emptyList(),
        onProgress: (String) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (isConversational(userPrompt)) {
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
                    onProgress("<b>Celebrimbot:</b> $response")
                    return@executeOnPooledThread
                }

                onProgress("<i>[🧠 Planner: deciding strategy...]</i>")
                val plannerPrompt = buildString {
                    if (conversationHistory.isNotEmpty()) {
                        append("Conversation history:\n")
                        conversationHistory.forEach { (role, content) -> append("$role: $content\n") }
                        append("\n")
                    }
                    append("Current request: $userPrompt\n\nProject Skeleton:\n$projectSkeleton")
                }
                val plannerJson = llmService.askPlanner(plannerPrompt)
                val plannerResult = parsePlannerResult(plannerJson)

                when (plannerResult.strategy) {
                    "direct" -> {
                        onProgress("<b>Celebrimbot:</b> ${plannerResult.response}")
                    }
                    "plan" -> {
                        val tasks = plannerResult.tasks
                        if (tasks.isEmpty()) {
                            onProgress("<b>Celebrimbot:</b> Could not generate a valid plan. Raw: <code>${plannerJson.take(300)}</code>")
                            return@executeOnPooledThread
                        }
                        onProgress("<i>[⚙️ Worker: 🖥️ Local Qwen → executing ${tasks.size} task(s)...]</i>")
                        var sharedContext = ""
                        for (task in tasks) {
                            val result = executeTaskWithRetry(task, onProgress, sharedContext, userPrompt, projectSkeleton)
                            if (task.action == "read_psi" && result.isSuccess) {
                                sharedContext = result.output
                                val escaped = result.output
                                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                                onProgress("<i>[📄 Read ${task.target}: ${result.output.length} chars]</i>")
                            }
                        }
                        onProgress("<b>Celebrimbot:</b> ✅ All tasks completed!")
                    }
                    else -> onProgress("<b>Celebrimbot:</b> Could not parse planner response. Raw: <code>${plannerJson.take(300)}</code>")
                }
            } catch (e: Exception) {
                onProgress("<b>Error:</b> ${e.message}")
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
            onProgress("<i>[⚙️ Worker: 🖥️ Local Qwen → task ${task.id} - ${task.action} (attempt ${retryCount + 1})]</i>")
            val result = performAction(task, lastError, context)
            if (result.isSuccess) {
                // Don't emit raw file content as progress — caller handles read_psi output
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
        val plannerResponse = llmService.askPlanner(escalationPrompt)
        val escalationResult = parsePlannerResult(plannerResponse)
        if (escalationResult.strategy == "direct" && escalationResult.response.isNotEmpty()) {
            onProgress("<b>Celebrimbot (Planner fallback):</b> ${escalationResult.response}")
        }
        return ActionResult(false, lastError)
    }

    private fun performAction(task: CelebrimbotTask, lastError: String, context: String = ""): ActionResult {
        return when (task.action) {
            "read_psi" -> readPsiAction(task)
            "write_code" -> writeCodeAction(task, lastError, context)
            "run_terminal" -> runTerminalAction(task)
            else -> ActionResult(false, "Unknown action: ${task.action}")
        }
    }

    private fun readPsiAction(task: CelebrimbotTask): ActionResult {
        val content = ReadAction.compute<String, Exception> {
            // Strip any path prefix — FilenameIndex needs just the filename
            val fileName = task.target?.substringAfterLast('/')?.substringAfterLast('\\')
                ?: return@compute "Error: No target file"
            val files = FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
            if (files.isEmpty()) return@compute "Error: File not found: $fileName"
            PsiManager.getInstance(project).findFile(files.first())?.text
                ?: "Error: Could not read PSI for $fileName"
        }
        return ActionResult(!content.startsWith("Error:"), content)
    }

    private fun writeCodeAction(task: CelebrimbotTask, lastError: String, context: String = ""): ActionResult {
        val workerPrompt = buildString {
            append("Write the code for this task: ${task.instruction}\nTarget file: ${task.target}\n")
            if (context.isNotEmpty()) append("Current file content to modify:\n$context\n")
            if (lastError.isNotEmpty()) append("Previous attempt failed: $lastError. Fix it.\n")
        }

        val codeResponse = llmService.askWorker(workerPrompt)
        val code = extractCode(codeResponse) ?: return ActionResult(false, "No code block found in Worker response.")

        val targetPath = task.target ?: return ActionResult(false, "No target file specified")
        val fileName = targetPath.substringAfterLast('/').substringAfterLast('\\')

        // Look for existing file in project
        var targetFile: VirtualFile? = ReadAction.compute<VirtualFile?, Exception> {
            FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project)).firstOrNull()
        }

        // If file doesn't exist, create it on disk under project base path
        if (targetFile == null) {
            val basePath = project.basePath ?: return ActionResult(false, "Cannot resolve project base path")
            val newFile = File(basePath, targetPath)
            newFile.parentFile.mkdirs()
            newFile.writeText(code)
            targetFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(newFile)
                ?: return ActionResult(false, "File created on disk but VFS refresh failed")
        }

        var success = false
        val vFile = targetFile
        ApplicationManager.getApplication().invokeAndWait {
            FileEditorManager.getInstance(project).openFile(vFile, true)
            WriteCommandAction.runWriteCommandAction(project, "Celebrimbot Worker", "Celebrimbot", {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                editor?.document?.setText(code)
                success = editor?.document != null
            })
        }
        return if (success) ActionResult(true, "<b>Celebrimbot:</b> ✅ Code written to $targetPath")
        else ActionResult(false, "Failed to write code to $targetPath")
    }

    private fun runTerminalAction(task: CelebrimbotTask): ActionResult {
        val command = task.command ?: return ActionResult(false, "No command provided")
        return try {
            val output = terminalService.executeCommand(command).get(30, java.util.concurrent.TimeUnit.SECONDS)
            if (output.contains("ERROR") || output.contains("failed")) ActionResult(false, output)
            else ActionResult(true, output)
        } catch (e: Exception) {
            ActionResult(false, e.message ?: "Terminal execution failed")
        }
    }

    private fun parsePlannerResult(json: String): PlannerResult {
        return try {
            val raw = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val jsonStr = Regex("""\{[\s\S]*\}""").find(raw)?.value ?: raw
            val obj = gson.fromJson(jsonStr, com.google.gson.JsonObject::class.java)
            val strategy = obj.get("strategy")?.asString ?: "unknown"
            when (strategy) {
                "direct" -> PlannerResult("direct", response = obj.get("response")?.asString ?: "")
                "plan" -> {
                    val type = object : TypeToken<List<CelebrimbotTask>>() {}.type
                    val tasks: List<CelebrimbotTask> = gson.fromJson(obj.getAsJsonArray("tasks"), type) ?: emptyList()
                    PlannerResult("plan", tasks = tasks)
                }
                else -> PlannerResult("unknown")
            }
        } catch (e: Exception) {
            PlannerResult("unknown")
        }
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

    private fun extractCode(text: String): String? =
        Regex("""```(?:\w+)?\n([\s\S]*?)```""").find(text)?.groupValues?.get(1)

    private fun isConversational(prompt: String): Boolean {
        val lower = prompt.trim().lowercase()
        // Fast pre-filter: explicit action verbs always go to planner without calling the model
        val actionVerbs = listOf(
            "modifica", "modifichi", "togli", "toglimi", "rimuovi", "elimina", "cancella",
            "aggiungi", "aggiungimi", "scrivi", "crea", "sistema", "aggiusta", "correggi",
            "refactor", "implementa", "esegui", "compila", "testa", "rinomina", "sposta",
            "modify", "remove", "delete", "add", "fix", "create", "write", "run", "compile",
            "test", "build", "generate", "refactor", "implement", "rename", "move", "update"
        )
        if (actionVerbs.any { lower.contains(it) }) return false
        // Fast pre-filter: pure greetings/questions always go to chat without calling the model
        val chatPatterns = listOf(
            "hi", "hello", "hey", "ciao", "salve", "buongiorno", "buonasera",
            "how are you", "who are you", "what are you", "come stai", "come va", "chi sei",
            "what files", "what do you see", "which files", "show files",
            "che file", "quali file", "cosa vedi"
        )
        if (chatPatterns.any { lower.contains(it) }) return true
        // For ambiguous messages, ask the model
        val routingPrompt = "<|im_start|>system\n${CelebrimbotLlmService.loadPrompt("router_system_prompt.txt")}<|im_end|>\n<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n"
        val embeddedEngine = CelebrimbotEmbeddedEngine.getInstance(project)
        return if (embeddedEngine.isModelDownloaded()) {
            val decision = embeddedEngine.askQuestion(routingPrompt, stopStrings = listOf("<|im_end|>", "<|im_start|>", "\n")).trim().uppercase()
            !decision.startsWith("PLAN")
        } else {
            prompt.trim().length < 60
        }
    }

    data class ActionResult(val isSuccess: Boolean, val output: String)

    companion object {
        fun getInstance(project: Project): CelebrimbotAgentOrchestrator = project.service()
    }
}
