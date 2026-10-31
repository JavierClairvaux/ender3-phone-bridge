package com.javcabr.printerbridge.server.mcp

import org.json.JSONArray
import org.json.JSONObject

/**
 * The result convention shared by every tool:
 *  - success: `{"ok": true, "data": {...}, "warnings": []}`
 *  - not possible on this firmware: `{"ok": false, "supported": false, "reason": "...", "needs": "Cap:Z_PROBE"}`
 *  - refused or failed: `{"ok": false, "error": "..."}`
 * A tool returns [unsupported] instead of sending a command the capability profile says is missing.
 */
object McpResults {
    fun ok(data: JSONObject = JSONObject(), warnings: List<String> = emptyList()): JSONObject =
        JSONObject().put("ok", true).put("data", data).put("warnings", JSONArray(warnings))

    fun unsupported(reason: String, needs: String): JSONObject =
        JSONObject().put("ok", false).put("supported", false).put("reason", reason).put("needs", needs)

    fun failed(error: String): JSONObject = JSONObject().put("ok", false).put("error", error)
}

/**
 * Null when the connected board reports `Cap:<name>:1`; otherwise the [McpResults.unsupported] result to
 * return from the tool (no printer, no profile yet, capability 0, or capability missing from a short
 * M115 list). Usage: `ctx.requireCap("Z_PROBE")?.let { return it }`.
 */
fun ToolContext.requireCap(name: String): JSONObject? {
    val p = ctl().profile ?: return McpResults.unsupported("no printer profile yet: the printer is not connected", "Cap:$name")
    return when (p.cap(name)) {
        true -> null
        false -> McpResults.unsupported("this firmware reports Cap:$name:0", "Cap:$name")
        null -> McpResults.unsupported("this firmware did not report Cap:$name in its M115 list", "Cap:$name")
    }
}

/** Null when [word] (a G/M code such as "M48") is not known to be missing on this firmware; else the unsupported result. */
fun ToolContext.requireCommand(word: String): JSONObject? {
    val p = ctl().profile ?: return null
    return if (p.isMissing(word)) McpResults.unsupported("this firmware answered 'Unknown command' to $word", "command:${word.uppercase()}") else null
}
