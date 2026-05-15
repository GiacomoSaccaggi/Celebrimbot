package com.github.giacomosaccaggi.celebrimbot.registry

import com.github.giacomosaccaggi.celebrimbot.model.CelebrimbotTool

/**
 * The Vault of the Rings — a central registry where every Ring of Power
 * is stored and can be retrieved by name. Planners query the vault to
 * discover available tools; workers query it to resolve an action string
 * into an executable tool instance.
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, CelebrimbotTool>()

    fun register(tool: CelebrimbotTool) { tools[tool.name] = tool }

    fun get(name: String): CelebrimbotTool? = tools[name]

    fun all(): List<CelebrimbotTool> = tools.values.toList()

    /**
     * Generates a compact JSON array describing every registered tool.
     * Injected into Aragorn's and Celebrimbor's system prompts so the
     * planners always know which deeds are available.
     */
    fun toJsonSchema(): String =
        tools.values.joinToString(",\n", "[\n", "\n]") { tool ->
            val params = tool.parameters.joinToString(",") { p ->
                "\"${p.name}\":{\"type\":\"${p.type}\"," +
                "\"required\":${p.required}," +
                "\"description\":\"${p.description}\"}"
            }
            "  {\"name\":\"${tool.name}\"," +
            "\"description\":\"${tool.description}\"," +
            "\"parameters\":{$params}}"
        }

    /**
     * Transforms every registered tool into an MCP-compliant JSON Schema list.
     * Used by McpRouter.handleToolsList() — the structure matches the MCP
     * tools/list response format exactly.
     */
    @Suppress("unused")
    fun toMcpToolList(): List<Map<String, Any>> = tools.values.map { tool ->
        val properties = tool.parameters.associate { p ->
            p.name to mapOf("type" to p.type, "description" to p.description)
        }
        val required = tool.parameters.filter { it.required }.map { it.name }
        val inputSchema: MutableMap<String, Any> = mutableMapOf(
            "type" to "object",
            "properties" to properties
        )
        if (required.isNotEmpty()) inputSchema["required"] = required
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "inputSchema" to inputSchema
        )
    }
}
