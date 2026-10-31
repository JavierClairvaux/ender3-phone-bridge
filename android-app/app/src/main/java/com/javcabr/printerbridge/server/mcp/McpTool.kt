package com.javcabr.printerbridge.server.mcp

import com.javcabr.printerbridge.printer.PrinterController
import org.json.JSONArray
import org.json.JSONObject

/** One MCP tool: [run] gets the call arguments and returns a JSON object (or any value) that becomes the text result. */
class McpTool(val name: String, val description: String, val schema: JSONObject, val run: (JSONObject) -> Any)

/** What a tool group needs from the app. One instance is shared by all groups. */
class ToolContext(val ctl: () -> PrinterController, val appInfo: () -> JSONObject)

/**
 * A group of tools that lives in its own file (`CoreTools.kt`, `FoundationTools.kt`, ...). A new batch adds
 * one file and one line in [McpRegistry]; it never touches another group's file.
 */
interface ToolGroup {
    fun tools(ctx: ToolContext): List<McpTool>
}

/** Small helpers for writing input schemas. */
object Schema {
    fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject().put("type", "object").put("properties", JSONObject().also { p -> props.forEach { p.put(it.first, it.second) } })
            .also { if (required.isNotEmpty()) it.put("required", JSONArray(required)) }

    fun str(description: String) = JSONObject().put("type", "string").put("description", description)
    fun int(description: String) = JSONObject().put("type", "integer").put("description", description)
    fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    fun num(description: String) = JSONObject().put("type", "number").put("description", description)
    fun strArray(description: String) = JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", description)
}
