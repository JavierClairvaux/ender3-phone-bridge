package com.javcabr.printerbridge.server

import com.javcabr.printerbridge.printer.PrinterController
import com.javcabr.printerbridge.server.mcp.McpInstructions
import com.javcabr.printerbridge.server.mcp.McpRegistry
import com.javcabr.printerbridge.server.mcp.ToolContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal MCP server over the Streamable HTTP transport (POST /mcp, JSON-RPC
 * 2.0, stateless: every POST gets a single application/json response; no SSE
 * stream, no session id). Implements initialize, ping, tools/list, tools/call
 * and accepts notifications with 202. The tools themselves live in `server/mcp/`
 * (one file per group, listed in [McpRegistry]).
 */
class McpHandler(private val ctl: () -> PrinterController, private val appInfo: () -> JSONObject = { JSONObject() }) {

    data class Reply(val status: Int, val body: String?)

    private val registry = McpRegistry(ToolContext(ctl, appInfo))

    fun handle(body: String): Reply {
        val parsed = try { JSONObject(body) } catch (e: Exception) {
            return try {
                val arr = JSONArray(body)
                val out = JSONArray()
                for (i in 0 until arr.length()) handleOne(arr.getJSONObject(i))?.let { out.put(it) }
                if (out.length() == 0) Reply(202, null) else Reply(200, out.toString())
            } catch (e2: Exception) {
                Reply(400, error(JSONObject.NULL, -32700, "parse error").toString())
            }
        }
        val r = handleOne(parsed) ?: return Reply(202, null)
        return Reply(200, r.toString())
    }

    private fun error(id: Any?, code: Int, msg: String) = JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", msg))

    private fun result(id: Any?, r: Any) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", r)

    /** Returns null for notifications / responses (no reply body). */
    private fun handleOne(m: JSONObject): JSONObject? {
        if (!m.has("method")) return null        // a response from the client: nothing to do
        val id = if (m.has("id")) m.get("id") else null
        val method = m.getString("method")
        if (id == null) return null               // notification (e.g. notifications/initialized)
        val params = m.optJSONObject("params") ?: JSONObject()
        return when (method) {
            "initialize" -> {
                val asked = params.optString("protocolVersion", LATEST)
                result(id, JSONObject()
                    .put("protocolVersion", if (asked in SUPPORTED) asked else LATEST)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "printer-bridge")
                        .put("version", appInfo().optString("version_name", "1.0")).put("schema_version", McpRegistry.SCHEMA_VERSION))
                    .put("instructions", McpInstructions.build(ctl())))
            }
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray(registry.tools.map {
                JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.schema)
            })))
            "tools/call" -> {
                val name = params.optString("name")
                val tool = registry.find(name) ?: return error(id, -32602, "unknown tool: $name")
                val args = params.optJSONObject("arguments") ?: JSONObject()
                val (text, isErr) = try {
                    val r = tool.run(args)
                    (if (r is JSONObject) r.toString(2) else r.toString()) to false
                } catch (e: Throwable) {
                    "${e.javaClass.simpleName}: ${e.message}" to true
                }
                result(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))).put("isError", isErr))
            }
            else -> error(id, -32601, "method not found: $method")
        }
    }

    companion object {
        const val LATEST = "2025-06-18"
        val SUPPORTED = setOf("2025-06-18", "2025-03-26", "2024-11-05")
    }
}
