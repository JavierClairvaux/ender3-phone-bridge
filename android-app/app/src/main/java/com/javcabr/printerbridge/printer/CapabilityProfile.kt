package com.javcabr.printerbridge.printer

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentSkipListSet

/**
 * What this printer can do, built once per connection from `M115` ([BoardInfo]), `M211` (soft limits)
 * and `M503` (settings) plus the matching entry of the quirks registry. MCP tools consult it before
 * sending anything ([hasCap], [isMissing]); a command the firmware answered with `echo:Unknown command`
 * is remembered in [knownMissing] for the rest of the connection.
 *
 * `M211` and `M503` are parsed leniently: Marlin 1.1 and 2.x word these lines differently, so a field
 * that is not found is null (never a guess).
 */
class CapabilityProfile(
    val board: BoardInfo,
    val quirk: Quirk?,
    m211: List<String>,
    m503: List<String>,
    val notes: List<String>,
) {
    val knownMissing: MutableSet<String> = ConcurrentSkipListSet(quirk?.knownMissing ?: emptySet())

    /** Soft endstop limits from `M211`, e.g. `x_max` = 235.0; empty if the reply had none. */
    val limits: Map<String, Double> = parseLimits(m211)
    /** Marlin 1.1 prints "Soft endstops: ON"; Marlin 2 prints "M211 S1 ; ON". */
    val softEndstopsOn: Boolean? = m211.firstNotNullOfOrNull {
        Regex("Soft endstops:\\s*(ON|OFF)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.let { v -> v.uppercase() == "ON" }
            ?: Regex("M211\\s+S([01])").find(it)?.groupValues?.get(1)?.let { v -> v == "1" }
    }
    /** Values read from `M503` (`m206_z`, `m851_z`, `m92_e` ...). */
    val settings: Map<String, Double> = parseSettings(m503)
    /** Leveling state stored in `M503` (`M420 S1`), null if not shown. */
    val levelingOn: Boolean? = m503.firstNotNullOfOrNull { Regex("M420\\s+S([01])").find(it)?.groupValues?.get(1) }?.let { it == "1" }
    /** Stored bilinear mesh: M503 lists it as `G29 W I<i> J<j> Z<height>` lines (heights relative to the leveling base, in mm). */
    val meshPoints: List<Triple<Int, Int, Double>> = m503.mapNotNull { l ->
        Regex("G29\\s+W\\s+I(\\d+)\\s+J(\\d+)\\s+Z$num").find(l)?.let { Triple(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toDouble()) }
    }
    /** Grid size as [columns, rows] from the stored points, or null if M503 showed no mesh. */
    val meshGrid: List<Int>? = if (meshPoints.isEmpty()) null else listOf(meshPoints.maxOf { it.first } + 1, meshPoints.maxOf { it.second } + 1)
    /** "bilinear" (stored `G29 W` points), "ubl", "mesh" or null (no mesh text in M503). */
    val meshType: String? = m503.joinToString("\n").lowercase().let { t ->
        when {
            meshPoints.isNotEmpty() || "bilinear" in t -> "bilinear"
            "unified bed" in t || Regex("\\bubl\\b").containsMatchIn(t) -> "ubl"
            "mesh bed" in t -> "mesh"
            else -> null
        }
    }

    /** True/false when M115 reported `Cap:<name>`; null when the capability list does not mention it (a short list). */
    fun cap(name: String): Boolean? = board.capabilities[name]
    fun hasCap(name: String) = cap(name) == true
    fun isMissing(word: String) = word.uppercase() in knownMissing
    fun learnMissing(word: String) { if (word.isNotEmpty()) knownMissing.add(word.uppercase()) }

    val probePresent get() = hasCap("Z_PROBE")
    val boardKey get() = quirk?.key

    fun toJson(): JSONObject = JSONObject()
        .put("board_key", boardKey ?: JSONObject.NULL)
        .put("board_name", quirk?.name ?: JSONObject.NULL)
        .put("firmware", board.firmwareName ?: JSONObject.NULL)
        .put("version", board.version ?: JSONObject.NULL)
        .put("family", board.family)
        .put("machine_type", board.machineType ?: JSONObject.NULL)
        .put("capabilities", JSONObject(board.capabilities.mapValues { it.value }))
        .put("probe_present", probePresent)
        .put("autolevel", hasCap("AUTOLEVEL"))
        .put("sd_card", hasCap("SDCARD"))
        .put("eeprom", hasCap("EEPROM"))
        .put("emergency_parser", hasCap("EMERGENCY_PARSER"))
        .put("autoreport_temp", hasCap("AUTOREPORT_TEMP"))
        .put("soft_endstops", softEndstopsOn ?: JSONObject.NULL)
        .put("limits", JSONObject(limits.mapValues { it.value }))
        .put("soft_limit_size", if (limits.containsKey("x_max") && limits.containsKey("y_max"))
            JSONObject().put("x", limits["x_max"]!! - (limits["x_min"] ?: 0.0)).put("y", limits["y_max"]!! - (limits["y_min"] ?: 0.0)) else JSONObject.NULL)
        .put("z_max", limits["z_max"] ?: JSONObject.NULL)
        .put("leveling_on", levelingOn ?: JSONObject.NULL)
        .put("mesh_type", meshType ?: JSONObject.NULL)
        .put("mesh_grid", meshGrid?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("mesh_z_min", meshPoints.minOfOrNull { it.third } ?: JSONObject.NULL)
        .put("mesh_z_max", meshPoints.maxOfOrNull { it.third } ?: JSONObject.NULL)
        .put("settings", JSONObject(settings.mapValues { it.value }))
        .put("known_missing_commands", JSONArray(knownMissing.toList()))
        .put("quirks", quirk?.quirks ?: JSONArray())
        .put("notes", JSONArray(notes))

    companion object {
        private val num = "(-?[0-9]+(?:\\.[0-9]+)?)"

        fun parseLimits(lines: List<String>): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            for (l in lines) {
                for (which in listOf("Min", "Max")) {
                    val m = Regex("$which:\\s*X$num\\s+Y$num\\s+Z$num").find(l) ?: continue
                    val p = which.lowercase()
                    out["x_$p"] = m.groupValues[1].toDouble(); out["y_$p"] = m.groupValues[2].toDouble(); out["z_$p"] = m.groupValues[3].toDouble()
                }
            }
            return out
        }

        fun parseSettings(lines: List<String>): Map<String, Double> {
            val out = LinkedHashMap<String, Double>()
            for (l in lines) {
                fun axes(code: String, prefix: String) {
                    if (!Regex("\\b$code\\b").containsMatchIn(l)) return
                    for (a in "XYZE") Regex("\\b$a$num").find(l.substringAfter(code))?.let { out["${prefix}_${a.lowercaseChar()}"] = it.groupValues[1].toDouble() }
                }
                axes("M92", "m92"); axes("M206", "m206"); axes("M851", "m851")
            }
            return out
        }
    }
}
