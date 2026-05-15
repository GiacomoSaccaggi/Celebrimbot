package com.github.giacomosaccaggi.celebrimbot.mcp

import com.github.giacomosaccaggi.celebrimbot.io.FileOperator
import com.github.giacomosaccaggi.celebrimbot.io.ProjectScanOperator
import com.github.giacomosaccaggi.celebrimbot.registry.ToolRegistry
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * The Beacon of Gondor — routes incoming JSON-RPC 2.0 requests to the
 * appropriate handler and returns a compact JSON-RPC response string.
 *
 * A single instance is shared across both the HTTP and Stdio transports.
 * All methods are stateless and thread-safe.
 *
 * MCP protocol version targeted: 2024-11-05
 */
class McpRouter(
    private val toolRegistry: ToolRegistry,
    private val fileOperator: FileOperator,
    private val scanOperator: ProjectScanOperator,
    private val serverVersion: String = "0.0.3"
) {
    /**
     * Parses [jsonRpc] as a JSON-RPC 2.0 request and dispatches it.
     * Returns an empty string for notification messages (no id) that
     * require no response — the transport must not write anything in that case.
     */
    fun handleRequest(jsonRpc: String): String {
        val request: JsonObject = try {
            McpTransport.GSON.fromJson(jsonRpc, JsonObject::class.java)
                ?: return McpTransport.error(null, -32700, "Parse error: null document")
        } catch (e: Exception) {
            return McpTransport.error(null, -32700, "Parse error: ${e.message}")
        }

        val id: JsonElement? = request.get("id")
        val method: String? = request.get("method")?.asString
        val params: JsonObject? = request.getAsJsonObject("params")

        // Notifications have no "id" and require no response
        if (id == null && method?.startsWith("notifications/") == true) return ""

        return when (method) {
            "initialize"               -> handleInitialize(id, params)
            "notifications/initialized"-> ""   // client confirmation — no response needed
            "tools/list"               -> handleToolsList(id)
            "tools/call"               -> handleToolsCall(id, params)
            "resources/list"           -> handleResourcesList(id)
            "resources/read"           -> handleResourcesRead(id, params)
            null                       -> McpTransport.error(id, -32600, "Invalid request: missing method")
            else                       -> McpTransport.error(id, -32601, "Method not found: $method")
        }
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    private fun handleInitialize(id: JsonElement?, @Suppress("UNUSED_PARAMETER") params: JsonObject?): String {
        // We accept any protocol version the client proposes and respond with ours.
        // The client decides whether to proceed.
        return McpTransport.result(
            id, mapOf(
                "protocolVersion" to MCP_PROTOCOL_VERSION,
                "capabilities" to mapOf(
                    "tools" to emptyMap<String, Any>(),
                    "resources" to emptyMap<String, Any>()
                ),
                "serverInfo" to mapOf(
                    "name" to "celebrimbot",
                    "version" to serverVersion
                )
            )
        )
    }

    private fun handleToolsList(id: JsonElement?): String =
        McpTransport.result(id, mapOf("tools" to toolRegistry.toMcpToolList()))

    private fun handleToolsCall(id: JsonElement?, params: JsonObject?): String {
        val toolName = params?.get("name")?.asString
            ?: return McpTransport.error(id, -32602, "Invalid params: missing 'name'")

        // Arguments may be absent for parameter-less tools
        val arguments: Map<String, String> = params.getAsJsonObject("arguments")
            ?.entrySet()
            ?.associate { (k, v) ->
                // Safely coerce any JSON primitive to String
                k to (if (v.isJsonNull) "" else v.asString)
            }
            ?: emptyMap()

        val tool = toolRegistry.get(toolName)
            ?: return McpTransport.error(id, -32602, "Unknown tool: $toolName")

        val toolResult = try {
            tool.execute(arguments)
        } catch (e: Exception) {
            return McpTransport.error(id, -32603, "Internal error executing '$toolName': ${e.message}")
        }

        return McpTransport.result(
            id, mapOf(
                "content" to listOf(
                    mapOf("type" to "text", "text" to toolResult.output)
                ),
                "isError" to !toolResult.success
            )
        )
    }

    private fun handleResourcesList(id: JsonElement?): String {
        val listing = scanOperator.listFiles()
        val resources = listing.lines()
            .filter { it.isNotBlank() }
            .take(100)
            .map { relativePath ->
                mapOf(
                    "uri" to "file:///$relativePath",
                    "name" to relativePath,
                    "mimeType" to guessMimeType(relativePath)
                )
            }
        return McpTransport.result(id, mapOf("resources" to resources))
    }

    private fun handleResourcesRead(id: JsonElement?, params: JsonObject?): String {
        val uri = params?.get("uri")?.asString
            ?: return McpTransport.error(id, -32602, "Invalid params: missing 'uri'")

        // Strip the file:/// prefix to get the relative path
        val relativePath = uri.removePrefix("file:///")
        val content = fileOperator.readFile(relativePath)

        if (content.startsWith("Error:")) {
            return McpTransport.error(id, -32602, content)
        }

        return McpTransport.result(
            id, mapOf(
                "contents" to listOf(
                    mapOf(
                        "uri" to uri,
                        "mimeType" to guessMimeType(relativePath),
                        "text" to content
                    )
                )
            )
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun guessMimeType(path: String): String = when (path.substringAfterLast('.', "")) {
        "kt", "kts"          -> "text/x-kotlin"
        "java"               -> "text/x-java"
        "py"                 -> "text/x-python"
        "js", "mjs", "cjs"  -> "text/javascript"
        "ts", "tsx"          -> "text/typescript"
        "json"               -> "application/json"
        "xml"                -> "application/xml"
        "html", "htm"        -> "text/html"
        "md"                 -> "text/markdown"
        "yaml", "yml"        -> "text/yaml"
        "sh", "bash", "zsh" -> "text/x-shellscript"
        "rs"                 -> "text/x-rust"
        "go"                 -> "text/x-go"
        "c", "h"             -> "text/x-c"
        "cpp", "cc", "hpp"  -> "text/x-c++"
        "cs"                 -> "text/x-csharp"
        "rb"                 -> "text/x-ruby"
        "swift"              -> "text/x-swift"
        "toml"               -> "text/x-toml"
        "gradle"             -> "text/x-groovy"
        else                 -> "text/plain"
    }

    companion object {
        private const val MCP_PROTOCOL_VERSION = "2024-11-05"
    }
}
