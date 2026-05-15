package com.github.giacomosaccaggi.celebrimbot.mcp

import com.google.gson.Gson
import com.google.gson.JsonElement

/**
 * The Signal Fire — low-level JSON-RPC 2.0 response formatting.
 *
 * CRITICAL: Uses compact Gson (no pretty-printing). MCP over Stdio uses
 * newline-delimited JSON — a single response must fit on exactly one line.
 * Any embedded newline would break the framing and cause Claude Desktop to hang.
 */
object McpTransport {

    // Compact serializer — never emits newlines inside a JSON value
    internal val GSON: Gson = Gson()

    /** Wraps [result] in a standard JSON-RPC 2.0 success envelope. */
    fun result(id: JsonElement?, result: Any): String =
        GSON.toJson(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "result" to result
            )
        )

    /**
     * Wraps an error in a standard JSON-RPC 2.0 error envelope.
     *
     * Standard error codes:
     *   -32700  Parse error
     *   -32600  Invalid request
     *   -32601  Method not found
     *   -32602  Invalid params
     *   -32603  Internal error
     */
    fun error(id: JsonElement?, code: Int, message: String): String =
        GSON.toJson(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "error" to mapOf("code" to code, "message" to message)
            )
        )
}
