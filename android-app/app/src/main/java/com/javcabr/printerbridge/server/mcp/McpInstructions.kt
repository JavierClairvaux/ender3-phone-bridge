package com.javcabr.printerbridge.server.mcp

import com.javcabr.printerbridge.printer.PrinterController
import com.javcabr.printerbridge.printer.RealPrinterConnection

/** The MCP `instructions` text, built from the connected board's capability profile at `initialize`. */
object McpInstructions {
    fun build(ctl: PrinterController): String {
        val sb = StringBuilder("Controls a 3D printer through an Android phone bridge (the app on the phone talks USB to the board). ")
        val p = ctl.profile
        if (p == null) {
            sb.append("No printer is connected right now (connection: ${ctl.connState}), so the board is unknown: call get_status, and board_info once it connects. ")
        } else {
            sb.append("Connected board: ${p.quirk?.name ?: "${p.board.firmwareName ?: "unknown firmware"} (not in the quirks registry)"}. ")
            val bed = p.limits.let { l -> if (l.containsKey("x_max") && l.containsKey("y_max")) "Soft limits (M211, not necessarily the physical bed): X ${fmt(l["x_min"] ?: 0.0)}..${fmt(l["x_max"]!!)}, Y ${fmt(l["y_min"] ?: 0.0)}..${fmt(l["y_max"]!!)}, Z up to ${fmt(l["z_max"] ?: 0.0)} mm. " else "" }
            sb.append(bed)
            sb.append(if (p.probePresent) "A Z probe is present (Cap:Z_PROBE)" else "There is no Z probe (Cap:Z_PROBE is 0): bed leveling is manual")
            sb.append(when (p.levelingOn) {
                true -> if (p.hasCap("AUTOLEVEL")) "; mesh leveling is ON (${p.meshType ?: "type not reported"}) and corrects every Z move. " else "; leveling is ON. "
                false -> "; leveling is OFF. "
                null -> if (p.hasCap("AUTOLEVEL")) "; automatic leveling is available. " else ". "
            })
            if (p.settings.containsKey("m206_z")) sb.append("Home offset M206 Z${fmt(p.settings["m206_z"]!!)}. ")
        }
        val lock = ctl.connection()?.let { if (it.allowsMotion()) "OFF (motion and heaters allowed)" else "ON (only read-only commands are sent: ${RealPrinterConnection.READ_ONLY.joinToString()}, M211 without arguments)" }
        if (lock != null) sb.append("Real-printer safety lock: $lock. ")
        sb.append("While a print is queued, printing or paused, home, motion and heater commands are refused. ")
        sb.append("Start with get_status. board_info lists the capabilities; a tool that the firmware cannot support returns {\"ok\": false, \"supported\": false, \"reason\", \"needs\"} instead of sending the command. ")
        sb.append("send_gcode runs raw G-code. start_print needs a file from list_files. ")
        sb.append("An operating guide resource (printer://operating-guide) is planned but not available yet.")
        return sb.toString()
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else String.format(java.util.Locale.US, "%.2f", v)
}
