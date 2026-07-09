package com.github.giacomosaccaggi.celebrimbot.registry.tools

import com.github.giacomosaccaggi.celebrimbot.io.*
import com.github.giacomosaccaggi.celebrimbot.model.*
import java.util.concurrent.TimeUnit

// ── FILE TOOLS ──────────────────────────────────────────────────────

class ReadFileTool(private val fileOp: FileOperator) : CelebrimbotTool {
    override val name = "read_psi"
    override val category = ToolCategory.FILE
    override val description = "Read the content of a file"
    override val parameters = listOf(
        ToolParam("target", description = "Relative file path", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val path = args["target"] ?: return ToolResult(false, "Missing 'target'")
        val content = fileOp.readFile(path)
        return ToolResult(!content.startsWith("Error:"), content)
    }
}

class DeleteFileTool(private val fileOp: FileOperator) : CelebrimbotTool {
    override val name = "delete_file"
    override val category = ToolCategory.FILE
    override val description = "Delete a file"
    override val parameters = listOf(
        ToolParam("target", description = "Relative file path", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val target = args["target"] ?: return ToolResult(false, "No target file specified")
        val success = fileOp.deleteFile(target)
        return if (success) ToolResult(true, "✅ Deleted $target")
        else ToolResult(false, "Failed to delete $target")
    }
}

/**
 * Raw file write — no LLM involved. Intended for MCP clients that generate
 * their own code and simply need to persist it to disk. The orchestrator's
 * write_code action (which invokes Frodo/Legolas) remains the internal path.
 */
class WriteFileTool(private val fileOp: FileOperator) : CelebrimbotTool {
    override val name = "write_file"
    override val category = ToolCategory.FILE
    override val description = "Write raw content to a file (creates or overwrites). Use this when you already have the final content."
    override val parameters = listOf(
        ToolParam("target", description = "Relative file path", required = true),
        ToolParam("content", description = "Full file content to write", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val target = args["target"] ?: return ToolResult(false, "Missing 'target'")
        val content = args["content"] ?: return ToolResult(false, "Missing 'content'")
        val success = fileOp.writeFile(target, content)
        return if (success) ToolResult(true, "✅ Written to $target")
        else ToolResult(false, "Failed to write to $target")
    }
}

// ── TERMINAL TOOLS ──────────────────────────────────────────────────

class RunTerminalTool(
    private val termOp: TerminalOperator,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) : CelebrimbotTool {
    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 60L
    }

    override val name = "run_terminal"
    override val category = ToolCategory.TERMINAL
    override val description = "Execute a shell command"
    override val parameters = listOf(
        ToolParam("command", description = "Shell command to execute", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val command = args["command"] ?: return ToolResult(false, "No command provided")
        return try {
            val result = termOp.executeCommand(command).get(timeoutSeconds, TimeUnit.SECONDS)
            ToolResult(result.exitCode == 0, result.output)
        } catch (e: Exception) {
            ToolResult(false, e.message ?: "Terminal execution failed")
        }
    }
}

// ── WEB TOOLS ───────────────────────────────────────────────────────

class WebSearchTool(private val webOp: WebSearchOperator) : CelebrimbotTool {
    override val name = "web_search"
    override val category = ToolCategory.WEB
    override val description = "Search the web via DuckDuckGo"
    override val parameters = listOf(
        ToolParam("query", description = "Search query", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val query = args["query"] ?: args["instruction"]
            ?: return ToolResult(false, "No search query provided")
        val result = webOp.search(query)
        return ToolResult(!result.startsWith("Error:"), result)
    }
}

class FetchPageTool(private val webOp: WebSearchOperator) : CelebrimbotTool {
    override val name = "fetch_page"
    override val category = ToolCategory.WEB
    override val description = "Fetch and read a URL"
    override val parameters = listOf(
        ToolParam("target", description = "URL to fetch", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val url = args["target"] ?: return ToolResult(false, "No URL provided")
        val result = webOp.fetchPage(url)
        return ToolResult(!result.startsWith("Error:"), result)
    }
}

// ── SCAN TOOLS ──────────────────────────────────────────────────────

class ListFilesTool(private val scanOp: ProjectScanOperator) : CelebrimbotTool {
    override val name = "list_files"
    override val category = ToolCategory.SCAN
    override val description = "List project files, optionally filtered by path and extension"
    override val parameters = listOf(
        ToolParam("target", description = "Sub-path to list (optional)"),
        ToolParam("extension", description = "File extension filter (optional)")
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val result = scanOp.listFiles(args["target"], args["extension"])
        return ToolResult(!result.startsWith("Error:"), result)
    }
}

class GrepFilesTool(private val scanOp: ProjectScanOperator) : CelebrimbotTool {
    override val name = "grep_files"
    override val category = ToolCategory.SCAN
    override val description = "Regex search across project files"
    override val parameters = listOf(
        ToolParam("pattern", description = "Regex pattern to search", required = true),
        ToolParam("extension", description = "File extension filter (optional)")
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val pattern = args["pattern"] ?: return ToolResult(false, "No grep pattern provided")
        val result = scanOp.grepFiles(pattern, args["extension"])
        return ToolResult(!result.startsWith("Error:"), result)
    }
}

class FindFileTool(private val scanOp: ProjectScanOperator) : CelebrimbotTool {
    override val name = "find_file"
    override val category = ToolCategory.SCAN
    override val description = "Find files by name fragment"
    override val parameters = listOf(
        ToolParam("query", description = "File name fragment to search", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val name = args["query"] ?: args["target"]
            ?: return ToolResult(false, "No file name provided")
        val result = scanOp.findByName(name)
        return ToolResult(!result.startsWith("Error:"), result)
    }
}

class FileStatsTool(private val scanOp: ProjectScanOperator) : CelebrimbotTool {
    override val name = "file_stats"
    override val category = ToolCategory.SCAN
    override val description = "Show line count and size of a file"
    override val parameters = listOf(
        ToolParam("target", description = "Relative file path", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val target = args["target"] ?: return ToolResult(false, "No target file specified")
        val result = scanOp.fileStats(target)
        return ToolResult(!result.startsWith("Error:"), result)
    }
}

// ── GIT TOOLS ───────────────────────────────────────────────────────

class GitStatusTool(private val gitOp: GitOperator) : CelebrimbotTool {
    override val name = "git_status"
    override val category = ToolCategory.GIT
    override val description = "Show working tree status"
    override val parameters = emptyList<ToolParam>()
    override fun execute(args: Map<String, String>) = ToolResult(true, gitOp.status())
}

class GitLogTool(private val gitOp: GitOperator) : CelebrimbotTool {
    override val name = "git_log"
    override val category = ToolCategory.GIT
    override val description = "Show recent commit history"
    override val parameters = emptyList<ToolParam>()
    override fun execute(args: Map<String, String>) = ToolResult(true, gitOp.log())
}

class GitDiffTool(private val gitOp: GitOperator) : CelebrimbotTool {
    override val name = "git_diff"
    override val category = ToolCategory.GIT
    override val description = "Show uncommitted changes, optionally for a specific file"
    override val parameters = listOf(
        ToolParam("target", description = "File path (optional, omit for full diff)")
    )
    override fun execute(args: Map<String, String>) =
        ToolResult(true, gitOp.diff(args["target"]))
}

class GitBlameTool(private val gitOp: GitOperator) : CelebrimbotTool {
    override val name = "git_blame"
    override val category = ToolCategory.GIT
    override val description = "Show per-line authorship of a file"
    override val parameters = listOf(
        ToolParam("target", description = "Relative file path", required = true)
    )
    override fun execute(args: Map<String, String>): ToolResult {
        val target = args["target"] ?: return ToolResult(false, "No target file specified")
        return ToolResult(true, gitOp.blame(target))
    }
}

class GitBranchTool(private val gitOp: GitOperator) : CelebrimbotTool {
    override val name = "git_branch"
    override val category = ToolCategory.GIT
    override val description = "Show current branch name"
    override val parameters = emptyList<ToolParam>()
    override fun execute(args: Map<String, String>) = ToolResult(true, gitOp.branch())
}
