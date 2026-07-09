package com.github.giacomosaccaggi.celebrimbot.model

/**
 * A Ring of Power — the universal interface for every capability
 * that the Fellowship can wield. Each tool wraps an existing Operator
 * method and exposes a self-describing JSON schema for the planners.
 */
interface CelebrimbotTool {
    val name: String
    val category: ToolCategory
    val description: String
    val parameters: List<ToolParam>

    fun execute(args: Map<String, String>): ToolResult
}

data class ToolParam(
    val name: String,
    val type: String = "string",
    val description: String,
    val required: Boolean = false
)

data class ToolResult(
    val success: Boolean,
    val output: String,
    val metadata: Map<String, String> = emptyMap()
)

enum class ToolCategory { FILE, TERMINAL, WEB, GIT, SCAN }
