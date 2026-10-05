package com.javcabr.printerbridge.server

import com.javcabr.printerbridge.printer.PrinterController
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal MCP server over the Streamable HTTP transport (POST /mcp, JSON-RPC
 * 2.0, stateless: every POST gets a single application/json response; no SSE
 * stream, no session id). Implements initialize, ping, tools/list, tools/call
 * and accepts notifications with 202.
 */
class McpHandler(private val ctl: () -> PrinterController) {

    data class Reply(val status: Int, val body: String?)

    private data class Tool(val name: String, val description: String, val schema: JSONObject, val run: (JSONObject) -> Any)

    private fun noArgs() = JSONObject().put("type", "object").put("properties", JSONObject())

    private val tools = listOf(
        Tool("start_print", "Start printing a G-code file already uploaded to the printer bridge (see list_files). Fails if a job is active or the printer is not connected.",
            JSONObject().put("type", "object").put("properties", JSONObject().put("file", JSONObject().put("type", "string").put("description", "File name as shown by list_files")))
                .put("required", JSONArray().put("file"))) { a -> ctl().startPrint(a.getString("file")) },
        Tool("get_status", "Current connection, temperatures, position and print-job state (state, progress %, elapsed/remaining time).", noArgs()) { ctl().statusJson() },
        Tool("pause", "Pause the running print. After the in-flight command completes: M85 idle-kill off, then (if parking is enabled, the position is known and motion is allowed) retract, raise Z, park at the configured XY, fan off. The bed stays hot; the nozzle cools after the configured standby time. Returns state \"paused\", or \"pausing\" while the in-flight command / park move is still running (see in_flight and pause.stage); pause.park_skipped says why it didn't park.", noArgs()) { ctl().pause() },
        Tool("resume", "Resume a paused print: reheats if needed (M109/M190 waits, shown as in_flight), primes, returns to the saved position, restores fan/feedrate/positioning modes, re-arms M85, then continues at the next line. Returns \"resuming\" until streaming truly continues, then \"printing\". Also withdraws a pause that hasn't started parking yet.", noArgs()) { ctl().resume() },
        Tool("cancel", "Cancel the active print (also while paused). Returns at once with cancel_requested=true; after any in-flight command (e.g. a firmware heat wait, which cannot be interrupted) completes: M85 off, heaters off, fan off, steppers off, and the job state becomes cancelled.", noArgs()) { ctl().cancel() },
        Tool("home", "Home all axes (G28). Refused while a print is active.", noArgs()) { ctl().home() },
        Tool("check_temps", "Read current hotend and bed temperatures and targets (fresh M105).", noArgs()) { ctl().checkTemps() },
        Tool("list_print_history", "Past print jobs, newest first.",
            JSONObject().put("type", "object").put("properties", JSONObject().put("limit", JSONObject().put("type", "integer").put("description", "Max entries (default 20)")))) { a ->
            JSONObject().put("history", ctl().history(a.optInt("limit", 20)))
        },
        Tool("list_files", "G-code files available to print.", noArgs()) { JSONObject().put("files", ctl().listFiles()) },
    )

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
                    .put("serverInfo", JSONObject().put("name", "printer-bridge").put("version", "1.0"))
                    .put("instructions", "Controls an Ender 3 (Marlin 1.1.6, no Z-probe) through an Android phone. " +
                        "Use get_status first. start_print needs a file from list_files."))
            }
            "ping" -> result(id, JSONObject())
            "tools/list" -> result(id, JSONObject().put("tools", JSONArray(tools.map {
                JSONObject().put("name", it.name).put("description", it.description).put("inputSchema", it.schema)
            })))
            "tools/call" -> {
                val name = params.optString("name")
                val tool = tools.firstOrNull { it.name == name } ?: return error(id, -32602, "unknown tool: $name")
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
