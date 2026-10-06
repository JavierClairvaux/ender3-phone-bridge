package com.javcabr.printerbridge.server

import android.content.Context
import android.util.Log
import com.javcabr.printerbridge.notify.TelegramNotifier
import com.javcabr.printerbridge.printer.BusyException
import com.javcabr.printerbridge.printer.PrinterController
import com.javcabr.printerbridge.printer.SafetyLockException
import com.javcabr.printerbridge.service.AppSettings
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/** What the HTTP layer needs from the foreground service. */
interface BridgeHost {
    val controller: PrinterController
    val settings: AppSettings
    val telegram: TelegramNotifier
    fun connectBackend(backend: String?): JSONObject
    fun disconnectPrinter(): JSONObject
    fun simulateError(kind: String): JSONObject
    fun applyRuntimeSettings()
    fun serviceInfo(): JSONObject
    /** GET /api/tls: certificate + issuance status (no secrets). */
    fun tlsStatus(): JSONObject
    /** POST /api/tls/issue: start an ACME issuance in the background; returns the job status at once. */
    fun tlsIssue(): JSONObject
    /** POST /api/tls/self_signed: replace the certificate with a new self-signed one. */
    fun tlsSelfSigned(confirm: Boolean): JSONObject
    fun tlsChainPem(): String?
    fun tlsCaPem(): String?
}

/**
 * Embedded HTTP server (NanoHTTPD) inside the foreground service, bound to
 * 0.0.0.0:<port>: web dashboard at "/", REST API under /api, MCP at /mcp.
 *
 * Auth: if an API token is set (generated on first run, shown in the app),
 * every non-GET request and every /mcp request must carry it as
 * "Authorization: Bearer <token>" (or "X-Api-Token: <token>", or ?token=).
 * GET endpoints (dashboard, status, temps, history) are readable without it.
 */
class ApiServer(private val context: Context, bindHost: String, port: Int, private val host: BridgeHost,
                val secure: Boolean = false) : NanoHTTPD(bindHost, port) {
    private val mcp = McpHandler { host.controller }

    override fun serve(s: IHTTPSession): Response {
        val path = s.uri.trimEnd('/').ifEmpty { "/" }
        return try {
            val needsAuth = s.method != Method.GET || path == "/mcp"
            if (needsAuth && !authorized(s)) return json(401, JSONObject().put("error", "missing or wrong API token"))
            route(s, path)
        } catch (e: Throwable) {
            val code = when (e) {
                is SafetyLockException -> 403
                is IllegalArgumentException, is org.json.JSONException -> 400
                is BusyException, is IllegalStateException -> 409
                is IOException -> 502
                else -> 500
            }
            if (code == 500) Log.e(TAG, "error on ${s.method} $path: ${e.javaClass.simpleName}")
            // org.json syntax errors quote the whole input, which may contain a secret: never echo it.
            val msg = if (e is org.json.JSONException && (e.message ?: "").contains(" at character ")) "malformed JSON body"
                      else redact(e.message ?: e.toString())
            json(code, JSONObject().put("error", msg).put("type", e.javaClass.simpleName))
        }
    }

    /** Removes the GoDaddy credentials (and their halves) from any text that leaves the server. */
    private fun redact(t: String): String {
        val sec = try { host.settings.secrets.get(com.javcabr.printerbridge.service.SecretStore.GODADDY) } catch (e: Exception) { null } ?: return t
        var out = t
        for (part in listOf(sec) + sec.split(':').filter { it.length >= 6 }) out = out.replace(part, "<redacted>")
        return out
    }

    private fun authorized(s: IHTTPSession): Boolean {
        val tok = host.settings.apiToken
        if (tok.isEmpty()) return true
        val h = s.headers["authorization"]?.removePrefix("Bearer ")?.trim()
        return h == tok || s.headers["x-api-token"] == tok || s.parms["token"] == tok
    }

    private fun route(s: IHTTPSession, path: String): Response {
        val ctl = host.controller
        val m = s.method
        return when {
            path == "/" || path == "/index.html" -> asset("dashboard.html", "text/html; charset=utf-8")
            path == "/mcp" && m == Method.POST -> {
                val r = mcp.handle(body(s))
                if (r.body == null) newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "")
                else newFixedLengthResponse(Response.Status.OK, "application/json", r.body)
            }
            path == "/mcp" -> json(405, JSONObject().put("error", "MCP endpoint: POST JSON-RPC only (no SSE stream)"))

            path == "/api/status" -> json(200, ctl.statusJson().put("service", host.serviceInfo()))
            path == "/api/temps" -> json(200, JSONObject().put("current", ctl.tempsJson()).put("history", ctl.tempHistoryJson())
                .put("history_format", "[t_ms, hotend, hotend_target, bed, bed_target]"))
            path == "/api/history" -> json(200, JSONObject().put("history", ctl.history(s.parms["limit"]?.toIntOrNull() ?: 50)))
            path == "/api/log" -> json(200, JSONObject().put("log", ctl.recentLog()))
            path == "/api/notifications" -> json(200, JSONObject().put("notifications", host.telegram.logJson()))
            path == "/api/files" && m == Method.GET -> json(200, JSONObject().put("files", ctl.listFiles()))
            path.startsWith("/api/files/") -> {
                val name = java.net.URLDecoder.decode(path.removePrefix("/api/files/"), "UTF-8")
                val f = ctl.safeFile(name)
                when (m) {
                    Method.PUT, Method.POST -> { val n = saveUpload(s, f); json(200, JSONObject().put("saved", name).put("bytes", n)) }
                    Method.DELETE -> json(if (f.delete()) 200 else 404, JSONObject().put("deleted", name))
                    else -> json(405, JSONObject().put("error", "use PUT to upload, DELETE to remove"))
                }
            }
            m != Method.POST && path.startsWith("/api/") && path !in GET_OK -> json(405, JSONObject().put("error", "use POST"))

            path == "/api/print" -> json(200, ctl.startPrint(jbody(s).getString("file")))
            path == "/api/pause" -> json(200, ctl.pause())
            path == "/api/resume" -> json(200, ctl.resume())
            path == "/api/cancel" -> json(200, ctl.cancel())
            path == "/api/home" -> json(200, ctl.home())
            path == "/api/check_temps" -> json(200, ctl.checkTemps())
            path == "/api/gcode" -> json(200, ctl.sendGcode(jbody(s).getString("command")))
            path == "/api/reset_board" -> {
                require(jbody(s).optBoolean("confirm")) { "send {\"confirm\": true} to reboot the printer board" }
                json(200, ctl.resetBoard())
            }
            path == "/api/connection" && m == Method.GET -> json(200, ctl.connectionJson())
            path == "/api/connection" -> {
                val b = jbody(s)
                when (b.optString("action", "connect")) {
                    "connect" -> json(200, host.connectBackend(b.optString("backend").ifEmpty { null }))
                    "disconnect" -> json(200, host.disconnectPrinter())
                    else -> throw IllegalArgumentException("action must be connect or disconnect")
                }
            }
            path == "/api/config/telegram" && m == Method.GET -> json(200, host.settings.telegramJson())
            path == "/api/config/telegram" -> {
                val b = jbody(s)
                val allowed = setOf("telegram_token", "telegram_chat_id", "telegram_base_url", "telegram_enabled", "notify_on_cancel")
                val bad = b.keys().asSequence().filter { it !in allowed }.toList()
                require(bad.isEmpty()) { "unknown keys $bad; allowed: $allowed" }
                host.settings.apply(b)
                json(200, host.settings.telegramJson())
            }
            path == "/api/config/telegram/test" -> json(200, host.telegram.sendTest())
            path == "/api/tls" && m == Method.GET -> json(200, host.tlsStatus())
            path == "/api/tls/cert.pem" && m == Method.GET -> host.tlsChainPem()?.let { newFixedLengthResponse(Response.Status.OK, "application/x-pem-file", it) }
                ?: json(404, JSONObject().put("error", "no certificate"))
            path == "/api/tls/ca.pem" && m == Method.GET -> host.tlsCaPem()?.let { newFixedLengthResponse(Response.Status.OK, "application/x-pem-file", it) }
                ?: json(404, JSONObject().put("error", "no certificate"))
            path == "/api/tls/issue" && m == Method.POST -> json(202, host.tlsIssue())
            path == "/api/tls/self_signed" && m == Method.POST -> json(200, host.tlsSelfSigned(jbody(s).optBoolean("confirm")))
            path == "/api/config" && m == Method.GET -> json(200, configJson())
            path == "/api/config" -> { val applied = host.settings.apply(jbody(s)); host.applyRuntimeSettings(); json(200, configJson().put("applied", applied)) }
            path == "/api/sim/error" -> json(200, host.simulateError(jbody(s).optString("kind", "thermal_runaway")))
            path == "/api/sim/config" -> {
                val b = jbody(s)
                val o = JSONObject()
                if (b.has("line_delay_ms")) o.put("fake_line_delay_ms", b.getLong("line_delay_ms"))
                if (b.has("time_scale")) o.put("fake_time_scale", b.getDouble("time_scale"))
                if (b.has("inject_resend_every")) o.put("fake_inject_resend_every", b.getInt("inject_resend_every"))
                host.settings.apply(o); host.applyRuntimeSettings()
                json(200, configJson())
            }
            else -> json(404, JSONObject().put("error", "no route ${m} $path"))
        }
    }

    private fun configJson(): JSONObject {
        val st = host.settings
        return JSONObject().put("backend", st.backend).put("http_port", st.httpPort).put("auto_connect", st.autoConnect)
            .put("keep_awake", st.keepAwake).put("real_safety_lock", st.realSafetyLock).put("real_settle_ms", st.realSettleMs)
            .put("idle_temp_poll", st.idleTempPoll).put("idle_shutdown_s", st.idleShutdownS)
            .put("fake_line_delay_ms", st.fakeLineDelayMs).put("fake_time_scale", st.fakeTimeScale)
            .put("fake_inject_resend_every", st.fakeInjectResendEvery).put("telegram", st.telegramJson())
            .put("api_token_set", st.apiToken.isNotEmpty())
            .put("pause", st.pauseJson())
            .put("tls", st.tlsJson())
    }

    private fun contentLength(s: IHTTPSession): Long = s.headers["content-length"]?.toLongOrNull() ?: 0L

    private fun body(s: IHTTPSession): String {
        val n = contentLength(s)
        require(n <= 1_000_000) { "body too large" }
        val buf = ByteArray(n.toInt())
        var off = 0
        val inp: InputStream = s.inputStream
        while (off < n) { val r = inp.read(buf, off, n.toInt() - off); if (r < 0) break; off += r }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private fun jbody(s: IHTTPSession): JSONObject { val b = body(s); return if (b.isBlank()) JSONObject() else JSONObject(b) }

    private fun saveUpload(s: IHTTPSession, f: File): Long {
        val n = contentLength(s)
        require(n in 1..MAX_UPLOAD) { "need a Content-Length between 1 and $MAX_UPLOAD bytes" }
        require(host.controller.statusJson().optJSONObject("job")?.let { it.optString("file") == f.name && it.optString("state") in setOf("queued", "printing", "pausing", "paused", "resuming") } != true) {
            "file is being printed" }
        val tmp = File(f.path + ".part")
        tmp.outputStream().use { out ->
            val buf = ByteArray(64 * 1024); var left = n
            while (left > 0) { val r = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; out.write(buf, 0, r); left -= r }
            require(left == 0L) { "upload truncated" }
        }
        if (!tmp.renameTo(f)) throw IOException("rename failed")
        return n
    }

    private fun asset(name: String, mime: String): Response =
        newFixedLengthResponse(Response.Status.OK, mime, context.assets.open(name).bufferedReader().use { it.readText() })

    private fun json(code: Int, o: JSONObject): Response {
        val st = object : Response.IStatus {
            override fun getDescription() = "$code ${REASONS[code] ?: "Status"}"
            override fun getRequestStatus() = code
        }
        return newFixedLengthResponse(st, "application/json", o.toString()).apply { addHeader("Cache-Control", "no-store") }
    }

    companion object {
        const val TAG = "PrinterBridge.http"
        const val MAX_UPLOAD = 64L * 1024 * 1024
        val REASONS = mapOf(200 to "OK", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 409 to "Conflict", 500 to "Internal Server Error", 502 to "Bad Gateway")
        val GET_OK = setOf("/api/connection", "/api/config", "/api/config/telegram", "/api/tls", "/api/tls/cert.pem", "/api/tls/ca.pem")
    }
}
