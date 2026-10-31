package com.javcabr.printerbridge.server.mcp

import org.json.JSONObject

/** The original nine tools: print control, status, history and files. */
object CoreTools : ToolGroup {
    override fun tools(ctx: ToolContext): List<McpTool> {
        val ctl = ctx.ctl
        return listOf(
            McpTool("start_print", "Start printing a G-code file already uploaded to the printer bridge (see list_files). Fails if a job is active or the printer is not connected.",
                Schema.obj("file" to Schema.str("File name as shown by list_files"), required = listOf("file"))) { a -> ctl().startPrint(a.getString("file")) },
            McpTool("get_status", "Current connection, temperatures, position and print-job state (state, progress %, elapsed/remaining time).", Schema.obj()) { ctl().statusJson() },
            McpTool("pause", "Pause the running print. After the in-flight command completes: M85 idle-kill off, then (if parking is enabled, the position is known and motion is allowed) retract, raise Z, park at the configured XY, fan off. The bed stays hot; the nozzle cools after the configured standby time. Returns state \"paused\", or \"pausing\" while the in-flight command / park move is still running (see in_flight and pause.stage); pause.park_skipped says why it didn't park.", Schema.obj()) { ctl().pause() },
            McpTool("resume", "Resume a paused print: reheats if needed (M109/M190 waits, shown as in_flight), primes, returns to the saved position, restores fan/feedrate/positioning modes, re-arms M85, then continues at the next line. Returns \"resuming\" until streaming truly continues, then \"printing\". Also withdraws a pause that hasn't started parking yet.", Schema.obj()) { ctl().resume() },
            McpTool("cancel", "Cancel the active print (also while paused). Returns at once with cancel_requested=true; after any in-flight command (e.g. a firmware heat wait, which cannot be interrupted) completes: M85 off, heaters off, fan off, steppers off, and the job state becomes cancelled.", Schema.obj()) { ctl().cancel() },
            McpTool("home", "Home all axes (G28). Refused while a print is active.", Schema.obj()) { ctl().home() },
            McpTool("check_temps", "Read current hotend and bed temperatures and targets (fresh M105).", Schema.obj()) { ctl().checkTemps() },
            McpTool("list_print_history", "Past print jobs, newest first.", Schema.obj("limit" to Schema.int("Max entries (default 20)"))) { a ->
                JSONObject().put("history", ctl().history(a.optInt("limit", 20)))
            },
            McpTool("list_files", "G-code files available to print.", Schema.obj()) { JSONObject().put("files", ctl().listFiles()) },
        )
    }
}
