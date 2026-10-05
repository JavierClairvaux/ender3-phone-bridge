package com.javcabr.printerbridge.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.javcabr.printerbridge.BuildConfig
import com.javcabr.printerbridge.printer.ControllerSettings
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * All runtime settings, in SharedPreferences ("settings"). Changed from the
 * Settings screen, the REST API (/api/config/...), service start extras, or a
 * one-shot local config file (see [importConfigFile]).
 */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        if (!prefs.contains(K_API_TOKEN)) prefs.edit().putString(K_API_TOKEN, randomToken()).apply()
    }

    var backend: String
        get() = prefs.getString(K_BACKEND, BuildConfig.DEFAULT_BACKEND) ?: "fake"
        set(v) { require(v in BACKENDS) { "backend must be one of $BACKENDS" }; prefs.edit().putString(K_BACKEND, v).apply() }
    var apiToken: String
        get() = prefs.getString(K_API_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(K_API_TOKEN, v).apply()
    var httpPort: Int
        get() = prefs.getInt("http_port", 8080)
        set(v) = prefs.edit().putInt("http_port", v).apply()
    var autoConnect: Boolean
        get() = prefs.getBoolean("auto_connect", true)
        set(v) = prefs.edit().putBoolean("auto_connect", v).apply()
    var keepAwake: Boolean
        get() = prefs.getBoolean("keep_awake", true)
        set(v) = prefs.edit().putBoolean("keep_awake", v).apply()

    // real printer
    /** Real backend only accepts M115/M105/M503/M114/M119 while this is on. ON until the hardware pass. */
    var realSafetyLock: Boolean
        get() = prefs.getBoolean("real_safety_lock", true)
        set(v) = prefs.edit().putBoolean("real_safety_lock", v).apply()
    var realSettleMs: Long
        get() = prefs.getLong("real_settle_ms", 750)
        set(v) = prefs.edit().putLong("real_settle_ms", v).apply()

    // controller
    var idleTempPoll: Boolean
        get() = prefs.getBoolean("idle_temp_poll", true)
        set(v) = prefs.edit().putBoolean("idle_temp_poll", v).apply()
    var idleShutdownS: Int
        get() = prefs.getInt("idle_shutdown_s", 300)
        set(v) = prefs.edit().putInt("idle_shutdown_s", v).apply()
    var notifyOnCancel: Boolean
        get() = prefs.getBoolean("notify_on_cancel", false)
        set(v) = prefs.edit().putBoolean("notify_on_cancel", v).apply()

    // pause / park (see PauseSettings)
    var pauseParkEnabled: Boolean
        get() = prefs.getBoolean("pause_park_enabled", true)
        set(v) = prefs.edit().putBoolean("pause_park_enabled", v).apply()
    var pauseParkX: Double
        get() = prefs.getFloat("pause_park_x", 10f).toDouble()
        set(v) = prefs.edit().putFloat("pause_park_x", v.toFloat()).apply()
    var pauseParkY: Double
        get() = prefs.getFloat("pause_park_y", 210f).toDouble()
        set(v) = prefs.edit().putFloat("pause_park_y", v.toFloat()).apply()
    var pauseZRaiseMm: Double
        get() = prefs.getFloat("pause_z_raise_mm", 10f).toDouble()
        set(v) { require(v in 0.0..100.0) { "pause_z_raise_mm must be 0..100" }; prefs.edit().putFloat("pause_z_raise_mm", v.toFloat()).apply() }
    var pauseRetractMm: Double
        get() = prefs.getFloat("pause_retract_mm", 5f).toDouble()
        set(v) { require(v in 0.0..15.0) { "pause_retract_mm must be 0..15" }; prefs.edit().putFloat("pause_retract_mm", v.toFloat()).apply() }
    var pauseExtraPurgeMm: Double
        get() = prefs.getFloat("pause_extra_purge_mm", 1.5f).toDouble()
        set(v) { require(v in 0.0..20.0) { "pause_extra_purge_mm must be 0..20" }; prefs.edit().putFloat("pause_extra_purge_mm", v.toFloat()).apply() }
    var pauseNozzleStandbyS: Int
        get() = prefs.getInt("pause_nozzle_standby_s", 300)
        set(v) = prefs.edit().putInt("pause_nozzle_standby_s", v).apply()

    fun pauseSettings() = com.javcabr.printerbridge.printer.PauseSettings(
        parkEnabled = pauseParkEnabled, parkX = pauseParkX, parkY = pauseParkY, zRaiseMm = pauseZRaiseMm,
        retractMm = pauseRetractMm, extraPurgeMm = pauseExtraPurgeMm, nozzleStandbyS = pauseNozzleStandbyS)

    fun pauseJson(): JSONObject = JSONObject().put("pause_park_enabled", pauseParkEnabled).put("pause_park_x", pauseParkX)
        .put("pause_park_y", pauseParkY).put("pause_z_raise_mm", pauseZRaiseMm).put("pause_retract_mm", pauseRetractMm)
        .put("pause_extra_purge_mm", pauseExtraPurgeMm).put("pause_nozzle_standby_s", pauseNozzleStandbyS)

    // telegram
    var telegramToken: String
        get() = prefs.getString("telegram_token", "") ?: ""
        set(v) = prefs.edit().putString("telegram_token", v.trim()).apply()
    var telegramChatId: String
        get() = prefs.getString("telegram_chat_id", "") ?: ""
        set(v) = prefs.edit().putString("telegram_chat_id", v.trim()).apply()
    var telegramBaseUrl: String
        get() = prefs.getString("telegram_base_url", DEFAULT_TELEGRAM_BASE) ?: DEFAULT_TELEGRAM_BASE
        set(v) = prefs.edit().putString("telegram_base_url", v.trim().trimEnd('/').ifEmpty { DEFAULT_TELEGRAM_BASE }).apply()
    var telegramEnabled: Boolean
        get() = prefs.getBoolean("telegram_enabled", true)
        set(v) = prefs.edit().putBoolean("telegram_enabled", v).apply()

    // fake simulator
    var fakeLineDelayMs: Long
        get() = prefs.getLong("fake_line_delay_ms", 15)
        set(v) = prefs.edit().putLong("fake_line_delay_ms", v).apply()
    var fakeTimeScale: Double
        get() = prefs.getFloat("fake_time_scale", 1f).toDouble()
        set(v) = prefs.edit().putFloat("fake_time_scale", v.toFloat()).apply()
    var fakeInjectResendEvery: Int
        get() = prefs.getInt("fake_inject_resend_every", 0)
        set(v) = prefs.edit().putInt("fake_inject_resend_every", v).apply()

    fun controllerSettings() = ControllerSettings(
        idleTempPoll = idleTempPoll, idleShutdownS = idleShutdownS, notifyOnCancel = notifyOnCancel, pause = pauseSettings())

    fun telegramJson(): JSONObject = JSONObject()
        .put("enabled", telegramEnabled).put("configured", telegramToken.isNotEmpty() && telegramChatId.isNotEmpty())
        .put("token", mask(telegramToken)).put("chat_id", telegramChatId).put("base_url", telegramBaseUrl)
        .put("notify_on_cancel", notifyOnCancel)

    /** Apply keys from a JSON object (REST body / config file). Returns the keys applied. */
    fun apply(o: JSONObject): List<String> {
        val applied = mutableListOf<String>()
        fun has(k: String) = o.has(k).also { if (it) applied.add(k) }
        if (has("backend")) backend = o.getString("backend")
        if (has("telegram_token")) telegramToken = o.getString("telegram_token")
        if (has("telegram_chat_id")) telegramChatId = o.get("telegram_chat_id").toString()
        if (has("telegram_base_url")) telegramBaseUrl = o.getString("telegram_base_url")
        if (has("telegram_enabled")) telegramEnabled = o.getBoolean("telegram_enabled")
        if (has("notify_on_cancel")) notifyOnCancel = o.getBoolean("notify_on_cancel")
        if (has("idle_temp_poll")) idleTempPoll = o.getBoolean("idle_temp_poll")
        if (has("idle_shutdown_s")) idleShutdownS = o.getInt("idle_shutdown_s")
        if (has("real_safety_lock")) realSafetyLock = o.getBoolean("real_safety_lock")
        if (has("real_settle_ms")) realSettleMs = o.getLong("real_settle_ms")
        if (has("fake_line_delay_ms")) fakeLineDelayMs = o.getLong("fake_line_delay_ms")
        if (has("fake_time_scale")) fakeTimeScale = o.getDouble("fake_time_scale")
        if (has("fake_inject_resend_every")) fakeInjectResendEvery = o.getInt("fake_inject_resend_every")
        if (has("api_token")) apiToken = o.getString("api_token")
        if (has("http_port")) httpPort = o.getInt("http_port")
        if (has("auto_connect")) autoConnect = o.getBoolean("auto_connect")
        if (has("keep_awake")) keepAwake = o.getBoolean("keep_awake")
        if (has("pause_park_enabled")) pauseParkEnabled = o.getBoolean("pause_park_enabled")
        if (has("pause_park_x")) pauseParkX = o.getDouble("pause_park_x").coerceIn(0.0, 220.0)
        if (has("pause_park_y")) pauseParkY = o.getDouble("pause_park_y").coerceIn(0.0, 220.0)
        if (has("pause_z_raise_mm")) pauseZRaiseMm = o.getDouble("pause_z_raise_mm")
        if (has("pause_retract_mm")) pauseRetractMm = o.getDouble("pause_retract_mm")
        if (has("pause_extra_purge_mm")) pauseExtraPurgeMm = o.getDouble("pause_extra_purge_mm")
        if (has("pause_nozzle_standby_s")) pauseNozzleStandbyS = o.getInt("pause_nozzle_standby_s")
        return applied
    }

    /**
     * One-shot local config: if <external files dir>/config.json exists
     * (adb push tools/telegram_config.local.json /sdcard/Android/data/<pkg>/files/config.json),
     * apply it and rename it to config.json.applied so secrets don't linger in a readable place.
     */
    fun importConfigFile(dir: File?) {
        val f = File(dir ?: return, "config.json")
        if (!f.isFile) return
        try {
            val keys = apply(JSONObject(f.readText()))
            Log.i("PrinterBridge", "applied config.json keys: $keys")
        } catch (e: Exception) { Log.e("PrinterBridge", "bad config.json: $e") }
        f.delete()
    }

    companion object {
        const val K_BACKEND = "backend"
        const val K_API_TOKEN = "api_token"
        const val DEFAULT_TELEGRAM_BASE = "https://api.telegram.org"
        val BACKENDS = listOf("fake", "real", "sim-usb")

        fun mask(s: String) = if (s.isEmpty()) "" else if (s.length <= 8) "****" else s.take(4) + "..." + s.takeLast(4)

        fun randomToken(): String {
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..20).map { chars[r.nextInt(chars.length)] }.joinToString("")
        }
    }
}
