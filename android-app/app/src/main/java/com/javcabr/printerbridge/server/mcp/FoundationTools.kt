package com.javcabr.printerbridge.server.mcp

import com.javcabr.printerbridge.printer.BusyException
import com.javcabr.printerbridge.printer.RealPrinterConnection
import com.javcabr.printerbridge.printer.SafetyLockException
import org.json.JSONArray
import org.json.JSONObject

/** Batch 0: raw G-code, the capability profile and version information. */
object FoundationTools : ToolGroup {
    /** Motion words whose `ok` comes when the move is queued, not finished: `wait_for_moves` follows them with M400. */
    private val QUEUED_MOVES = setOf("G0", "G1", "G2", "G3", "G5")
    /** Commands that need a Z probe; not sent when M115 says `Cap:Z_PROBE:0`. */
    private val PROBE_COMMANDS = setOf("G29", "G30", "G31", "G32", "M401", "M402")

    override fun tools(ctx: ToolContext): List<McpTool> = listOf(
        McpTool("send_gcode",
            "Send raw G-code to the printer: one `command` or a list `commands` (run in order). Goes through the same guard as POST /api/gcode: " +
                "while a print is queued/printing/paused only read-only commands (M105, M114, M115, M119, M503, M211 with no arguments) are allowed " +
                "and G28, moves and heater commands are refused; with the real-printer safety lock ON the same read-only set is all that is sent. " +
                "A command the firmware is known not to have (see board_info known_missing_commands), or a probe command (G29, G30, G31, G32, M401, M402) when the board reports no Z probe, is not sent and comes back as supported:false. " +
                "`wait_for_moves` follows G0/G1/G2/G3/G5 with M400 so the reply means the move finished. " +
                "`stop_on_error` (default true) stops the list at the first refused or failed command; the rest are listed as skipped. " +
                "Prefer a dedicated tool when there is one: this does no clamping to soft limits or lifting before XY moves.",
            Schema.obj(
                "command" to Schema.str("One G-code line, e.g. \"M105\""),
                "commands" to Schema.strArray("Several lines, sent one after another"),
                "wait_for_moves" to Schema.bool("Send M400 after G0/G1/G2/G3/G5 (default false)"),
                "stop_on_error" to Schema.bool("Stop at the first refused or failed command (default true)"),
            )) { a -> sendGcode(ctx, a) },
        McpTool("board_info",
            "What this printer can do: firmware name/version and build date, machine type, the M115 capability map, soft limits and bed size (M211), " +
                "probe present, leveling/mesh state (M503), SD, emergency parser, temperature autoreport, known_missing_commands (learned from " +
                "'Unknown command' answers) and the matching known-board quirks. Built when the printer connects; no printer connected gives supported:false.",
            Schema.obj()) { board(ctx) },
        McpTool("server_info",
            "Version information: app version name/code and git commit, MCP schema_version and supported protocol versions, backend and printer profile name.",
            Schema.obj()) { serverInfo(ctx) },
    )

    private fun board(ctx: ToolContext): JSONObject {
        val p = ctx.ctl().profile ?: return McpResults.unsupported("no printer profile yet: the printer is not connected (connection state: ${ctx.ctl().connState})", "connected printer")
        return McpResults.ok(p.toJson(), p.notes)
    }

    fun serverInfo(ctx: ToolContext): JSONObject {
        val ctl = ctx.ctl()
        val p = ctl.profile
        return McpResults.ok(JSONObject()
            .put("app", ctx.appInfo())
            .put("mcp", JSONObject().put("schema_version", McpRegistry.SCHEMA_VERSION)
                .put("protocol_versions", JSONArray(com.javcabr.printerbridge.server.McpHandler.SUPPORTED.sorted()))
                .put("latest_protocol_version", com.javcabr.printerbridge.server.McpHandler.LATEST))
            .put("backend", ctl.connection()?.kind ?: JSONObject.NULL)
            .put("connection_state", ctl.connState)
            .put("printer_profile", p?.let { it.boardKey ?: it.board.firmwareName } ?: JSONObject.NULL))
    }

    private fun sendGcode(ctx: ToolContext, a: JSONObject): JSONObject {
        val cmds = ArrayList<String>()
        a.optString("command").takeIf { it.isNotBlank() }?.let { cmds.add(it) }
        a.optJSONArray("commands")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(cmds::add) }
        if (cmds.isEmpty()) return McpResults.failed("give `command` or a non-empty `commands` list")
        // the guard looks at the first word only, and the link appends "\n": an embedded line break would smuggle a second command past it
        if (cmds.any { '\n' in it || '\r' in it }) return McpResults.failed("a command must be a single line (no line breaks): pass several in `commands`")
        if (cmds.size > 200) return McpResults.failed("at most 200 commands per call (got ${cmds.size})")
        val waitMoves = a.optBoolean("wait_for_moves", false)
        val stopOnError = a.optBoolean("stop_on_error", true)

        val results = JSONArray()
        val skipped = JSONArray()
        var stopped = false
        for (raw in cmds) {
            if (stopped) { skipped.put(raw); continue }
            val r = one(ctx, raw.trim())
            if (waitMoves && r.optBoolean("ok") && RealPrinterConnection.commandWord(raw) in QUEUED_MOVES) {
                val w = one(ctx, "M400")
                r.put("moves_finished", w.optBoolean("ok"))
                if (!w.optBoolean("ok")) r.put("ok", false).put("error", "M400 after the move failed: ${w.optString("error")}")
            }
            results.put(r)
            if (!r.optBoolean("ok") && stopOnError) stopped = true
        }
        val allOk = (0 until results.length()).all { results.getJSONObject(it).optBoolean("ok") } && skipped.length() == 0
        return JSONObject().put("ok", allOk)
            .put("data", JSONObject().put("results", results).put("skipped", skipped))
            .put("warnings", JSONArray())
    }

    /** Send one line; never throws. */
    private fun one(ctx: ToolContext, cmd: String): JSONObject {
        val word = RealPrinterConnection.commandWord(cmd)
        ctx.requireCommand(word)?.let { return it.put("command", cmd).put("sent", false) }
        // only a definite Cap:Z_PROBE:0 blocks it: a short M115 list (capability not reported) must not stop a real probe
        if (word in PROBE_COMMANDS && ctx.ctl().profile?.cap("Z_PROBE") == false)
            ctx.requireCap("Z_PROBE")?.let { return it.put("command", cmd).put("sent", false) }
        return try {
            val reply = ctx.ctl().sendGcode(cmd).optJSONArray("reply") ?: JSONArray()
            val lines = (0 until reply.length()).map { reply.getString(it) }
            val bad = lines.firstOrNull { it.startsWith("Error:") || it.contains("Unknown command") }
            JSONObject().put("command", cmd).put("sent", true).put("ok", bad == null).put("reply", JSONArray(lines))
                .also { if (bad != null) it.put("error", bad) }
        } catch (e: Exception) {
            val refused = e is BusyException || e is SafetyLockException
            JSONObject().put("command", cmd).put("ok", false).put("refused", refused)
                .put("error", e.message ?: e.javaClass.simpleName).also { if (refused) it.put("sent", false) }
        }
    }
}
