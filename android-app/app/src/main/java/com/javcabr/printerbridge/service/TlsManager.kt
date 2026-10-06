package com.javcabr.printerbridge.service

import android.content.Context
import android.util.Log
import com.javcabr.printerbridge.printer.RealPrinterConnection
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocketFactory

/**
 * Owns the HTTPS server certificate: a PKCS12 keystore at <filesDir>/tls/server.p12
 * (password random, kept in [SecretStore]) plus tls/meta.json (source, issuance
 * history; no secrets). Certificate parsing/creation runs in Python
 * (tls_tool.py, `cryptography`).
 */
/** Python calls progress.step(msg) during issuance (messages are secret-free; redacted anyway). */
interface AcmeProgress { fun step(msg: String) }

class TlsManager(private val context: Context, private val settings: AppSettings, private val secrets: SecretStore) {
    val dir = File(context.filesDir, "tls").apply { mkdirs() }
    val p12 = File(dir, "server.p12")
    private val metaFile = File(dir, "meta.json")
    @Volatile private var infoCache: Pair<Long, JSONObject>? = null

    private fun py() = RealPrinterConnection.py(context).getModule("tls_tool")

    private fun password(): String = secrets.get(SecretStore.P12_PASSWORD) ?: run {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val pw = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        secrets.put(SecretStore.P12_PASSWORD, pw); pw
    }

    fun hasCert() = p12.isFile && p12.length() > 0

    /** mtime+size fingerprint, used to detect a replaced keystore and hot-reload HTTPS. */
    fun certStamp(): Long = if (hasCert()) p12.lastModified() * 31 + p12.length() else 0L

    @Synchronized
    fun meta(): JSONObject = try { if (metaFile.isFile) JSONObject(metaFile.readText()) else JSONObject() } catch (e: Exception) { JSONObject() }

    @Synchronized
    fun updateMeta(f: (JSONObject) -> Unit) {
        val m = meta(); f(m)
        val tmp = File(dir, "meta.json.tmp"); tmp.writeText(m.toString()); tmp.renameTo(metaFile)
    }

    /** Certificate details (public), cached per keystore version. */
    fun certInfo(): JSONObject? {
        if (!hasCert()) return null
        val stamp = certStamp()
        infoCache?.let { if (it.first == stamp) return it.second }
        return try {
            JSONObject(py().callAttr("p12_info", p12.path, password()).toString()).also { infoCache = stamp to it }
        } catch (e: Exception) { Log.e(TAG, "reading keystore failed: ${e.javaClass.simpleName}"); null }
    }

    @Synchronized
    fun makeSelfSigned(): JSONObject {
        val info = JSONObject(py().callAttr("make_self_signed", p12.path, password(), settings.tlsDomain).toString())
        updateMeta { it.put("source", "self-signed").put("directory", JSONObject.NULL).put("installed_at", System.currentTimeMillis()) }
        infoCache = null
        Log.i(TAG, "self-signed certificate installed for ${info.optString("subject_cn")} (expires ${info.optString("not_after")})")
        return info
    }

    /** Make sure a certificate exists when TLS is enabled (self-signed until ACME replaces it). */
    fun ensureCert() {
        if (!settings.tlsEnabled) return
        if (!hasCert()) { makeSelfSigned(); return }
        // a self-signed certificate for a different (old) tls_domain is replaced right away
        val want = settings.tlsDomain.ifEmpty { "printer-bridge.local" }
        if (source() == "self-signed" && certInfo()?.optString("subject_cn") != want) makeSelfSigned()
    }

    fun sslServerSocketFactory(): SSLServerSocketFactory {
        val ks = KeyStore.getInstance("PKCS12")
        p12.inputStream().use { ks.load(it, password().toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password().toCharArray())
        return fi.iki.elonen.NanoHTTPD.makeSSLSocketFactory(ks, kmf)
    }

    fun chainPem(): String = py().callAttr("chain_pem", p12.path, password()).toString()
    fun caPem(): String = py().callAttr("ca_pem", p12.path, password()).toString()

    fun source(): String = if (!hasCert()) "none" else meta().optString("source", "unknown")

    // ------------------------------------------------------------------ ACME issuance

    private val jobLock = Any()
    @Volatile private var job: JSONObject = JSONObject().put("state", "idle")

    fun jobJson(): JSONObject = synchronized(jobLock) { JSONObject(job.toString()) }
    fun issuing(): Boolean = synchronized(jobLock) { job.optString("state") == "running" }

    /** Replace the GoDaddy credentials (and their halves) in any text before it is stored, logged or sent. */
    fun redact(t: String?): String {
        if (t == null) return ""
        val sec = try { secrets.get(SecretStore.GODADDY) } catch (e: Exception) { null } ?: return t
        var out: String = t
        for (part in listOf(sec) + sec.split(':').filter { it.length >= 6 }) out = out.replace(part, "<redacted>")
        return out
    }

    /** Why a (re)issuance is due, or null. Never anything while TLS is disabled or ACME isn't configured. */
    fun renewalReason(): String? {
        if (!settings.tlsEnabled || !acmeConfigured()) return null
        if (!hasCert()) return "no certificate"
        val m = meta()
        if (m.optString("source") != "acme") return "self-signed certificate"
        if (m.optString("directory") != settings.acmeDirectory) return "ACME directory changed to ${settings.acmeDirectory}"
        val info = certInfo() ?: return "certificate unreadable"
        if (info.optString("subject_cn") != settings.tlsDomain) return "tls_domain changed"
        val days = info.optDouble("days_left", 0.0)
        if (days < RENEW_DAYS) return "expires in ${"%.1f".format(days)} days"
        return null
    }

    fun acmeConfigured() = settings.tlsDomain.isNotEmpty() && settings.acmeEmail.isNotEmpty() && secrets.has(SecretStore.GODADDY)

    /**
     * Starts an issuance on a background thread and returns the job status immediately.
     * [onDone] runs on that thread with (success, message). Throws IllegalStateException
     * (HTTP 409) if TLS is off, ACME isn't configured, a print job is active, or one is running.
     */
    fun issueAsync(trigger: String, printJobActive: Boolean, onDone: (Boolean, String) -> Unit): JSONObject {
        check(settings.tlsEnabled) { "TLS is disabled (tls_enabled=false): enable it before issuing a certificate" }
        check(settings.tlsDomain.isNotEmpty()) { "tls_domain is not set" }
        check(settings.acmeEmail.isNotEmpty()) { "acme_email is not set" }
        check(secrets.has(SecretStore.GODADDY)) { "GoDaddy credentials are not set" }
        check(!printJobActive) { "a print job is active: certificate issuance waits until it is finished (it shares the Python runtime and CPU with the printer link)" }
        val creds = secrets.get(SecretStore.GODADDY)!!
        val env = settings.acmeDirectory; val domain = settings.tlsDomain; val email = settings.acmeEmail; val zone = settings.effectiveZone()
        synchronized(jobLock) {
            check(job.optString("state") != "running") { "an issuance is already running" }
            job = JSONObject().put("state", "running").put("trigger", trigger).put("directory", env).put("domain", domain)
                .put("started_at", System.currentTimeMillis()).put("steps", org.json.JSONArray())
        }
        val progress = object : AcmeProgress {
            override fun step(msg: String) {
                val m = redact(msg)
                Log.i(TAG, "acme: $m")
                synchronized(jobLock) { job.getJSONArray("steps").put("${System.currentTimeMillis()} $m") }
            }
        }
        Thread({
            var ok = false; var msg: String
            try {
                progress.step("starting ($trigger) for $domain via $env, DNS zone $zone")
                val res = JSONObject(RealPrinterConnection.py(context).getModule("acme_issue")
                    .callAttr("issue", dir.path, env, email, domain, zone, creds, p12.path, password(), progress).toString())
                infoCache = null
                val cert = res.getJSONObject("cert"); val cleanup = res.getJSONObject("cleanup")
                updateMeta {
                    it.put("source", "acme").put("directory", env).put("last_renewal", System.currentTimeMillis())
                        .put("installed_at", System.currentTimeMillis()).put("last_error", JSONObject.NULL)
                        .put("last_cleanup", cleanup)
                }
                ok = true
                msg = "certificate for $domain from Let's Encrypt ${env} (issuer ${cert.optString("issuer_cn")}), expires ${cert.optString("not_after")}" +
                    (if (cleanup.optBoolean("restored", true)) "" else "; WARNING: TXT cleanup did not verify")
                synchronized(jobLock) { job.put("state", "succeeded").put("result", JSONObject().put("cert", cert).put("cleanup", cleanup).put("seconds", res.opt("seconds"))) }
            } catch (t: Throwable) {
                msg = redact("${t.javaClass.simpleName}: ${t.message}").take(600)
                updateMeta { it.put("last_error", msg).put("last_error_at", System.currentTimeMillis()) }
                synchronized(jobLock) { job.put("state", "failed").put("error", msg) }
                Log.e(TAG, "acme issuance failed: $msg")
            }
            synchronized(jobLock) { job.put("finished_at", System.currentTimeMillis()) }
            try { onDone(ok, msg) } catch (t: Throwable) { Log.e(TAG, "onDone: ${t.javaClass.simpleName}") }
        }, "acme-issue").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
        return jobJson()
    }

    fun statusJson(httpsRunning: Boolean, httpsError: String?): JSONObject {
        val m = meta()
        val info = certInfo()
        fun n(v: Any?) = v ?: JSONObject.NULL
        return JSONObject()
            .put("enabled", settings.tlsEnabled).put("https_port", settings.httpsPort)
            .put("https_running", httpsRunning).put("https_error", n(httpsError))
            .put("domain", settings.tlsDomain.ifEmpty { null } ?: JSONObject.NULL)
            .put("source", source())
            .put("acme_directory_setting", settings.acmeDirectory)
            .put("cert_directory", n(m.opt("directory")))
            .put("issuer", n(info?.optString("issuer_cn")?.let { cn -> info.optString("issuer_o").takeIf { it.isNotEmpty() }?.let { "$cn ($it)" } ?: cn }))
            .put("subject", n(info?.optString("subject_cn")))
            .put("san", info?.optJSONArray("san_dns") ?: JSONObject.NULL)
            .put("not_before", n(info?.optString("not_before"))).put("not_after", n(info?.optString("not_after")))
            .put("days_left", n(info?.optDouble("days_left")))
            .put("serial", n(info?.optString("serial"))).put("sha256", n(info?.optString("sha256")))
            .put("chain_issuers", info?.optJSONArray("chain_issuers") ?: JSONObject.NULL)
            .put("last_renewal", n(m.opt("last_renewal"))).put("last_error", n(m.opt("last_error")))
            .put("godaddy_credentials_set", secrets.has(SecretStore.GODADDY))
            .put("acme_email", settings.acmeEmail.ifEmpty { null } ?: JSONObject.NULL)
            .put("dns_zone", settings.effectiveZone().ifEmpty { null } ?: JSONObject.NULL)
            .put("acme_configured", acmeConfigured())
            .put("renewal_due", renewalReason() ?: JSONObject.NULL)
            .put("next_check_at", n(m.opt("next_check_at")))
            .put("issuance", jobJson())
    }

    /** Short form for /api/status.service. */
    fun shortJson(httpsRunning: Boolean): JSONObject {
        val info = certInfo()
        return JSONObject().put("enabled", settings.tlsEnabled).put("https_port", settings.httpsPort)
            .put("https_running", httpsRunning).put("source", source())
            .put("domain", settings.tlsDomain.ifEmpty { null } ?: JSONObject.NULL)
            .put("not_after", info?.optString("not_after") ?: JSONObject.NULL)
            .put("days_left", info?.optDouble("days_left") ?: JSONObject.NULL)
    }

    companion object {
        const val TAG = "PrinterBridge.tls"
        const val RENEW_DAYS = 30.0
    }
}
