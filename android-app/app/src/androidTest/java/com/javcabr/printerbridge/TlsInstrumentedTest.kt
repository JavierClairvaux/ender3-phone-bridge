package com.javcabr.printerbridge

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javcabr.printerbridge.service.AppSettings
import com.javcabr.printerbridge.service.SecretStore
import com.javcabr.printerbridge.service.TlsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class TlsInstrumentedTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val TAG = "PrinterBridge.test"

    @Test
    fun secretStore_roundTrip_ciphertextOnlyAtRest() {
        val s = SecretStore(ctx)
        val value = "TESTKEY_abc123:TESTSECRET_def456"
        s.put("unit_test_secret", value)
        assertEquals(value, s.get("unit_test_secret"))
        val raw = s.rawStored("unit_test_secret")!!
        assertFalse("plaintext stored", raw.contains("TESTKEY") || raw.contains("TESTSECRET"))
        val prefsFile = java.io.File(ctx.applicationInfo.dataDir, "shared_prefs/secrets.xml")
        Thread.sleep(300)
        if (prefsFile.isFile) assertFalse(prefsFile.readText().contains("TESTSECRET"))
        // a second encryption of the same value differs (fresh GCM IV)
        s.put("unit_test_secret", value)
        assertNotEquals(raw, s.rawStored("unit_test_secret"))
        s.remove("unit_test_secret")
        assertNull(s.get("unit_test_secret"))
        // credentials are write-only through settings: validation errors never contain the value
        val st = AppSettings(ctx)
        try { st.apply(JSONObject().put("godaddy_credentials", "not a key TESTSECRET")); fail("accepted") }
        catch (e: IllegalArgumentException) { assertFalse(e.message!!.contains("TESTSECRET")) }
        Log.i(TAG, "PASS secret store: AES-GCM ciphertext at rest, fresh IV, write-only validation")
    }

    @Test
    fun selfSignedCert_servedOverHttps_verifiesAgainstItsCa_andHotSwaps() {
        val st = AppSettings(ctx)
        st.tlsDomain = "printer.example.test"
        val tls = TlsManager(ctx, st, st.secrets)
        tls.p12.delete()
        val info = tls.makeSelfSigned()
        assertEquals("printer.example.test", info.getString("subject_cn"))
        assertEquals("Printer Bridge local CA", info.getString("issuer_cn"))
        assertTrue(info.getJSONArray("san_dns").toString().contains("localhost"))
        assertEquals(2, info.getInt("chain_length"))

        fun serveAndFetch(): String {
            val srv = object : fi.iki.elonen.NanoHTTPD("127.0.0.1", 18443) {
                override fun serve(session: IHTTPSession) = newFixedLengthResponse("hello-tls")
            }
            srv.makeSecure(tls.sslServerSocketFactory(), null)
            srv.start(5000, false)
            try {
                val ca = CertificateFactory.getInstance("X.509").generateCertificate(tls.caPem().byteInputStream())
                val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", ca) }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
                val c = java.net.URL("https://localhost:18443/").openConnection() as HttpsURLConnection
                c.sslSocketFactory = ssl.socketFactory
                val body = c.inputStream.bufferedReader().readText()
                val serial = (c.serverCertificates[0] as java.security.cert.X509Certificate).serialNumber.toString(16)
                c.disconnect()
                assertEquals("hello-tls", body)
                return serial
            } finally { srv.stop() }
        }
        val s1 = serveAndFetch()
        assertEquals(info.getString("serial"), s1)
        // replacing the keystore (atomic rename) yields a new cert on the next listener
        val stamp = tls.certStamp()
        Thread.sleep(1100)
        val info2 = tls.makeSelfSigned()
        assertNotEquals(stamp, tls.certStamp())
        val s2 = serveAndFetch()
        assertEquals(info2.getString("serial"), s2)
        assertNotEquals(s1, s2)
        assertEquals("self-signed", tls.source())
        Log.i(TAG, "PASS self-signed cert over HTTPS verified against its CA; swap $s1 -> $s2")
    }
}
