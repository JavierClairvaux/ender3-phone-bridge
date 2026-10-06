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

    companion object { const val TAG = "PrinterBridge.tls" }
}
