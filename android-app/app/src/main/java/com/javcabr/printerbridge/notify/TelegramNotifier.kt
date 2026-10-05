package com.javcabr.printerbridge.notify

import android.util.Log
import com.javcabr.printerbridge.service.AppSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Telegram Bot API notifications: POST {base_url}/bot{token}/sendMessage with
 * chat_id + text. base_url defaults to https://api.telegram.org and is
 * configurable so the logic can be tested against a local fake endpoint.
 * Sends happen on a background thread with 3 attempts (0 s, 2 s, 5 s).
 * Every attempt is recorded (token masked) for /api/notifications.
 */
class TelegramNotifier(private val settings: AppSettings) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "telegram").apply { isDaemon = true } }
    private val log = ArrayDeque<JSONObject>()

    fun notify(kind: String, text: String) {
        val entry = JSONObject().put("kind", kind).put("text", text).put("queued_at", System.currentTimeMillis())
        if (!settings.telegramEnabled) { record(entry.put("result", "skipped: disabled")); return }
        if (settings.telegramToken.isEmpty() || settings.telegramChatId.isEmpty()) {
            record(entry.put("result", "skipped: token/chat_id not configured")); return
        }
        record(entry.put("result", "pending"))
        exec.execute { deliver(entry) }
    }

    /** Synchronous send for the "send test message" button/endpoint. */
    fun sendTest(): JSONObject {
        val entry = JSONObject().put("kind", "test").put("text", "Printer Bridge test message").put("queued_at", System.currentTimeMillis())
        record(entry)
        if (settings.telegramToken.isEmpty() || settings.telegramChatId.isEmpty()) return entry.put("result", "skipped: token/chat_id not configured")
        return exec.submit<JSONObject> { deliver(entry) }.get()
    }

    private fun deliver(entry: JSONObject): JSONObject {
        val delays = longArrayOf(0, 2000, 5000)
        var last = ""
        for ((i, d) in delays.withIndex()) {
            if (d > 0) Thread.sleep(d)
            try {
                val (code, body) = post(entry.getString("text"))
                val ok = code == 200 && try { JSONObject(body).optBoolean("ok") } catch (_: Exception) { false }
                last = "HTTP $code ${body.take(200)}"
                if (ok) {
                    synchronized(log) { entry.put("result", "sent").put("attempts", i + 1).put("response", last).put("sent_at", System.currentTimeMillis()) }
                    Log.i(TAG, "telegram ${entry.getString("kind")} sent (attempt ${i + 1})")
                    return entry
                }
                if (code in 400..499 && code != 429) break   // bad token/chat id: retrying won't help
            } catch (e: Exception) { last = e.toString() }
            Log.w(TAG, "telegram attempt ${i + 1} failed: $last")
        }
        synchronized(log) { entry.put("result", "failed").put("response", last) }
        return entry
    }

    private fun post(text: String): Pair<Int, String> {
        val url = URL("${settings.telegramBaseUrl}/bot${settings.telegramToken}/sendMessage")
        val body = "chat_id=" + URLEncoder.encode(settings.telegramChatId, "UTF-8") +
            "&text=" + URLEncoder.encode(text, "UTF-8") + "&disable_web_page_preview=true"
        val c = url.openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val resp = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return code to resp
    }

    private fun record(e: JSONObject) = synchronized(log) { log.addLast(e); while (log.size > 50) log.removeFirst() }

    fun logJson(): JSONArray = synchronized(log) { JSONArray(log.map { JSONObject(it.toString()) }.reversed()) }

    companion object {
        const val TAG = "PrinterBridge.tg"

        fun fmtDuration(s: Long): String = if (s >= 3600) "%dh%02dm".format(s / 3600, s % 3600 / 60) else "%dm%02ds".format(s / 60, s % 60)

        fun jobMessage(job: JSONObject): String {
            val state = job.optString("state")
            val file = job.optString("file")
            val dur = fmtDuration(job.optLong("elapsed_s"))
            val lines = "${job.optInt("lines_done")}/${job.optInt("lines_total")} lines"
            return when (state) {
                "done" -> "[Printer Bridge] Print finished: $file\nTime: $dur, $lines"
                "error" -> "[Printer Bridge] PRINT ERROR: $file\nAt ${job.optDouble("progress_pct")}% ($lines) after $dur\nError: ${job.optString("error")}"
                "cancelled" -> "[Printer Bridge] Print cancelled: $file at ${job.optDouble("progress_pct")}% after $dur"
                else -> "[Printer Bridge] Print $state: $file"
            }
        }
    }
}
