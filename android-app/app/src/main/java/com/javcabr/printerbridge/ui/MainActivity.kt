package com.javcabr.printerbridge.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.javcabr.printerbridge.service.AppSettings
import com.javcabr.printerbridge.service.PrinterService
import org.json.JSONObject

/**
 * Minimal native UI: connection + print state from the foreground service,
 * plus connect/disconnect, backend switch, pause/resume/cancel and settings.
 * Also the target of USB_DEVICE_ATTACHED for the CH340.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var backendBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        startForegroundService(Intent(this, PrinterService::class.java))

        status = TextView(this).apply { typeface = Typeface.MONOSPACE; textSize = 12f; setPadding(0, 24, 0, 0); setTextIsSelectable(true) }
        backendBtn = btn("Backend") { cycleBackend() }
        val row1 = row(btn("Connect") { bg { svc()?.connectBackend(null) } }, btn("Disconnect") { bg { svc()?.disconnectPrinter() } }, backendBtn)
        val row2 = row(btn("Pause") { bg { svc()?.controller?.pause() } }, btn("Resume") { bg { svc()?.controller?.resume() } },
            btn("Cancel") { bg { svc()?.controller?.cancel() } })
        val row3 = row(btn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) },
            btn("Stop service") { stopService(Intent(this, PrinterService::class.java)) })
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
                addView(TextView(this@MainActivity).apply { text = "Printer Bridge"; textSize = 22f })
                addView(row1); addView(row2); addView(row3); addView(status)
            })
        })
        handleAttach(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleAttach(intent) }

    private fun handleAttach(i: Intent?) {
        if (i?.action == "android.hardware.usb.action.USB_DEVICE_ATTACHED")
            handler.postDelayed({ svc()?.let { if (it.settings.backend == "real") it.connectAsync() } }, 1000)
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun svc() = PrinterService.instance

    private fun cycleBackend() {
        val s = svc() ?: return
        val all = AppSettings.BACKENDS
        val next = all[(all.indexOf(s.settings.backend) + 1) % all.size]
        bg { s.connectBackend(next) }
    }

    private fun bg(f: () -> Any?) = Thread {
        val msg = try { f(); null } catch (e: Throwable) { e.message ?: e.toString() }
        if (msg != null) runOnUiThread { lastError = msg; render() }
    }.start()

    private var lastError: String? = null

    private fun render() {
        val s = svc()
        if (s == null) { status.text = "service not running (tap Connect after it starts)"; return }
        val st = s.controller.statusJson()
        val info = s.serviceInfo()
        val c = st.getJSONObject("connection")
        val t = st.getJSONObject("temps")
        val j = st.optJSONObject("job")
        backendBtn.text = "Backend: ${s.settings.backend}"
        fun v(o: JSONObject, k: String) = if (o.isNull(k)) "-" else o.get(k).toString()
        val sb = StringBuilder()
        sb.append("CONNECTION\n  state:    ${c.optString("state")} (${v(c, "backend")})\n")
        if (!c.isNull("error")) sb.append("  error:    ${c.optString("error")}\n")
        sb.append("  firmware: ${v(c, "firmware")}\n  machine:  ${v(c, "machine_type")}\n  halted:   ${c.optBoolean("halted")}\n")
        c.optJSONObject("details")?.let { d ->
            listOf("usb_device", "chip_version", "open_ms", "reset_detected_on_open", "dtr_rts_values_sent", "dtr_ever_asserted", "safety_lock")
                .filter { d.has(it) && !d.isNull(it) }.forEach { sb.append("  $it: ${d.get(it)}\n") }
        }
        sb.append("\nTEMPERATURES\n  hotend: ${v(t, "hotend")} / ${v(t, "hotend_target")}\n  bed:    ${v(t, "bed")} / ${v(t, "bed_target")}\n")
        sb.append("\nPRINT JOB\n")
        if (j == null) sb.append("  none\n") else {
            sb.append("  ${j.optString("state").uppercase()}  ${j.optString("file")}\n")
            sb.append("  progress: ${j.optDouble("progress_pct")}%  (${j.optInt("lines_done")}/${j.optInt("lines_total")} lines)\n")
            sb.append("  elapsed:  ${j.optLong("elapsed_s")}s  remaining: ${v(j, "remaining_s")}s (${j.optString("remaining_source")})\n")
            if (!j.isNull("error")) sb.append("  error:    ${j.optString("error")}\n")
        }
        sb.append("\nENDPOINTS (port ${info.optInt("http_port")})\n")
        val urls = info.getJSONArray("urls")
        for (i in 0 until urls.length()) sb.append("  ${urls.getString(i)}   (MCP: ${urls.getString(i)}mcp)\n")
        sb.append("  API token: ${s.settings.apiToken.ifEmpty { "(none: API open)" }}\n")
        sb.append("\nSERVICE\n  uptime ${info.optLong("uptime_s")}s, wake lock ${info.optBoolean("wake_lock_held")}, doze ${info.optBoolean("device_idle_mode")}\n")
        sb.append("  telegram: ${if (s.settings.telegramToken.isNotEmpty() && s.settings.telegramChatId.isNotEmpty()) "configured" else "NOT configured"}\n")
        sb.append("  real-printer safety lock: ${if (s.settings.realSafetyLock) "ON (read-only G-code only)" else "OFF"}\n")
        lastError?.let { sb.append("\nLAST UI ERROR: $it\n") }
        status.text = sb.toString()
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    private fun row(vararg views: Button) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it) } }
}
