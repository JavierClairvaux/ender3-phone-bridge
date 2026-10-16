package com.javcabr.printerbridge.printer

import org.json.JSONObject

/**
 * What the board says about itself in its M115 reply: firmware name and version, machine type, UUID
 * and the `Cap:NAME:0|1` capability lines. This is the second layer of board detection (the first
 * is the USB id, see [com.javcabr.printerbridge.usb.UsbCatalog]); it tells a Marlin 1.1.6 Ender 3
 * board from a Marlin 2 SKR no matter how it is attached.
 *
 * Example captured from an SKR Mini E3 V3.0 on 2026-10-14:
 * `FIRMWARE_NAME:Marlin bugfix-2.0.x (Oct 11 2022 14:32:21) SOURCE_CODE_URL:github.com/MarlinFirmware/Marlin
 *  PROTOCOL_VERSION:1.0 MACHINE_TYPE:Ender-3 EXTRUDER_COUNT:1 UUID:cede2a2f-...`
 */
class BoardInfo(
    val firmwareName: String?,
    val machineType: String?,
    val uuid: String?,
    val capabilities: Map<String, Boolean>,
) {
    /** The version word after "Marlin " (`1.1.6.2`, `bugfix-2.0.x`, `2.1.2.5`), or null if it is not Marlin. */
    val version: String? = firmwareName?.let { Regex("^Marlin\\s+(\\S+)").find(it)?.groupValues?.get(1) }

    /** "marlin-1.1", "marlin-2.x", "marlin" (version not recognised) or "unknown". */
    val family: String = when {
        version == null -> "unknown"
        version.startsWith("1.1") -> "marlin-1.1"
        Regex("^(?:[a-z]+-)?2\\.[0-9x]").containsMatchIn(version) -> "marlin-2.x"
        else -> "marlin"
    }

    /**
     * Firmware kind from the name, by lowercase substring match as OctoPrint's firmware detection does
     * (comm.py: repetier, reprapfirmware, malyan, klipper, prusa-firmware ...). This app's protocol layer
     * is written for Marlin; anything else is flagged by [warning].
     */
    val kind: String = firmwareName?.lowercase()?.let { n ->
        when {
            "klipper" in n -> "klipper"
            "repetier" in n || "anet_a8" in n -> "repetier"
            "reprapfirmware" in n -> "reprapfirmware"
            "malyan" in n -> "malyan"
            "prusa-firmware" in n -> "prusa"
            "marlin" in n -> "marlin"
            else -> "unknown"
        }
    } ?: "unknown"

    /** Non-null when the firmware is not one this app was written and tested for. */
    val warning: String? get() = if (kind == "marlin") null
        else "firmware '$firmwareName' is not Marlin ($kind): the app's M85, resend and pause logic assume Marlin and are untested here"

    /** The identity line arrived whole (name, machine type and UUID); the `Cap:` list can still be short. */
    val complete get() = firmwareName != null && machineType != null && uuid != null

    private fun cap(name: String) = capabilities[name] == true

    /** The firmware reads M112/M108/M410 out of order, so a cancel can interrupt a heat wait. */
    val emergencyParser get() = cap("EMERGENCY_PARSER")
    val eeprom get() = cap("EEPROM")
    val autolevel get() = cap("AUTOLEVEL")
    val zProbe get() = cap("Z_PROBE")
    val thermalProtection get() = cap("THERMAL_PROTECTION")
    val sdCard get() = cap("SDCARD")

    fun toJson(): JSONObject = JSONObject()
        .put("firmware", firmwareName ?: JSONObject.NULL)
        .put("version", version ?: JSONObject.NULL)
        .put("family", family)
        .put("kind", kind)
        .put("warning", warning ?: JSONObject.NULL)
        .put("machine_type", machineType ?: JSONObject.NULL)
        .put("uuid", uuid ?: JSONObject.NULL)
        .put("emergency_parser", emergencyParser)
        .put("eeprom", eeprom)
        .put("autolevel", autolevel)
        .put("z_probe", zProbe)
        .put("thermal_protection", thermalProtection)
        .put("sd_card", sdCard)
        .put("capabilities", JSONObject(capabilities.mapValues { it.value }))

    companion object {
        /** Parse the lines of an M115 reply (the `ok` line may or may not be included). Null if there is no FIRMWARE_NAME. */
        fun parse(reply: List<String>): BoardInfo? {
            val id = reply.firstOrNull { it.contains("FIRMWARE_NAME:") } ?: return null
            val name = id.substringAfter("FIRMWARE_NAME:").substringBefore(" SOURCE_CODE_URL").trim()
            val machine = Regex("MACHINE_TYPE:(.*?) EXTRUDER_COUNT").find(id)?.groupValues?.get(1)
            val uuid = Regex("UUID:(\\S+)").find(id)?.groupValues?.get(1)
            val caps = LinkedHashMap<String, Boolean>()
            for (l in reply) {
                val m = Regex("^\\s*Cap:([A-Z0-9_]+):([01])\\s*$").find(l) ?: continue
                caps[m.groupValues[1]] = m.groupValues[2] == "1"
            }
            return BoardInfo(name, machine, uuid, caps)
        }
    }
}
