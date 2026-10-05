package com.javcabr.printerbridge.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.javcabr.printerbridge.service.AppSettings
import com.javcabr.printerbridge.service.PrinterService

/** Settings screen: Telegram bot token/chat id, API token, safety lock, simulator knobs. */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val s = PrinterService.instance?.settings ?: AppSettings(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun label(t: String) = col.addView(TextView(this).apply { text = t; setPadding(0, 20, 0, 0) })
        fun edit(t: String, v: String, secret: Boolean = false) = EditText(this).apply {
            setText(v); isSingleLine = true
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }.also { label(t); col.addView(it) }
        fun check(t: String, v: Boolean) = CheckBox(this).apply { text = t; isChecked = v }.also { col.addView(it) }

        col.addView(TextView(this).apply { text = "Telegram"; textSize = 18f })
        val tgToken = edit("Bot token (from @BotFather)", s.telegramToken, secret = true)
        val tgChat = edit("Chat ID", s.telegramChatId)
        val tgBase = edit("API base URL (leave as https://api.telegram.org)", s.telegramBaseUrl)
        val tgOn = check("Send notifications (print done / error)", s.telegramEnabled)
        val tgCancel = check("Also notify on cancel", s.notifyOnCancel)
        col.addView(TextView(this).apply { text = "\nAPI / service"; textSize = 18f })
        val apiTok = edit("API token (empty = no auth on the LAN API)", s.apiToken)
        val port = edit("HTTP port (restart service to apply)", s.httpPort.toString())
        val awake = check("Keep CPU awake while the service runs", s.keepAwake)
        val idlePoll = check("Poll temperatures while idle (M105)", s.idleTempPoll)
        col.addView(TextView(this).apply { text = "\nReal printer"; textSize = 18f })
        val lock = check("Safety lock: only read-only G-code (M115/M105/M503/M114/M119)", s.realSafetyLock)
        col.addView(TextView(this).apply { text = "\nPause / park"; textSize = 18f })
        val parkOn = check("Park the nozzle on pause (retract, raise Z, move away)", s.pauseParkEnabled)
        val parkX = edit("Park X (mm)", s.pauseParkX.toString())
        val parkY = edit("Park Y (mm)", s.pauseParkY.toString())
        val zRaise = edit("Z raise on pause (mm)", s.pauseZRaiseMm.toString())
        val retract = edit("Retract on pause (mm)", s.pauseRetractMm.toString())
        val purge = edit("Extra purge on resume (mm)", s.pauseExtraPurgeMm.toString())
        val standby = edit("Cool nozzle after paused for (s; 0 = never)", s.pauseNozzleStandbyS.toString())
        col.addView(TextView(this).apply { text = "\nSimulator (fake backend)"; textSize = 18f })
        val delay = edit("Fake per-line delay (ms)", s.fakeLineDelayMs.toString())
        val scale = edit("Fake time scale (temperature/homing speed-up)", s.fakeTimeScale.toString())
        val result = TextView(this)

        col.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                s.telegramToken = tgToken.text.toString(); s.telegramChatId = tgChat.text.toString()
                s.telegramBaseUrl = tgBase.text.toString(); s.telegramEnabled = tgOn.isChecked; s.notifyOnCancel = tgCancel.isChecked
                s.apiToken = apiTok.text.toString().trim(); port.text.toString().toIntOrNull()?.let { s.httpPort = it }
                s.keepAwake = awake.isChecked; s.idleTempPoll = idlePoll.isChecked; s.realSafetyLock = lock.isChecked
                delay.text.toString().toLongOrNull()?.let { s.fakeLineDelayMs = it }
                scale.text.toString().toDoubleOrNull()?.let { s.fakeTimeScale = it }
                s.pauseParkEnabled = parkOn.isChecked
                try {
                    parkX.text.toString().toDoubleOrNull()?.let { s.pauseParkX = it.coerceIn(0.0, 220.0) }
                    parkY.text.toString().toDoubleOrNull()?.let { s.pauseParkY = it.coerceIn(0.0, 220.0) }
                    zRaise.text.toString().toDoubleOrNull()?.let { s.pauseZRaiseMm = it }
                    retract.text.toString().toDoubleOrNull()?.let { s.pauseRetractMm = it }
                    purge.text.toString().toDoubleOrNull()?.let { s.pauseExtraPurgeMm = it }
                    standby.text.toString().toIntOrNull()?.let { s.pauseNozzleStandbyS = it }
                } catch (e: IllegalArgumentException) { result.text = "Not saved: ${e.message}"; return@setOnClickListener }
                PrinterService.instance?.applyRuntimeSettings()
                result.text = "Saved."
            }
        })
        col.addView(Button(this).apply {
            text = "Send Telegram test message"
            setOnClickListener {
                result.text = "sending..."
                Thread {
                    val r = PrinterService.instance?.telegram?.sendTest()?.toString(2) ?: "service not running"
                    runOnUiThread { result.text = r }
                }.start()
            }
        })
        col.addView(result)
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
