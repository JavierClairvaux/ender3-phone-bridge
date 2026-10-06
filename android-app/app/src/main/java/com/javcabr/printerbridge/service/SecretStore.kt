package com.javcabr.printerbridge.service

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted secret store: values are AES-256-GCM encrypted with a key that
 * lives in the Android Keystore (non-exportable; hardware-backed where the device
 * supports it) and stored as base64(iv || ciphertext+tag) in the private
 * SharedPreferences file "secrets".
 *
 * Protects against copying the app's data files (backup, file-level root access,
 * a stolen data partition image): the ciphertext is useless without the Keystore
 * key. It does NOT protect against code running as this app's uid (or a root user
 * who injects into it), which can ask the Keystore to decrypt. See docs/DESIGN.md.
 *
 * Never log the values passed in or returned.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        return String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN), Charsets.UTF_8)
    }

    fun has(name: String) = prefs.contains(name)

    @Synchronized
    fun remove(name: String) { prefs.edit().remove(name).apply() }

    /** The stored ciphertext only (for tests proving nothing is stored in plain text). */
    fun rawStored(name: String): String? = prefs.getString(name, null)

    companion object {
        const val ALIAS = "printerbridge_secrets_v1"
        const val IV_LEN = 12
        const val GODADDY = "godaddy_credentials"
        const val P12_PASSWORD = "tls_p12_password"
    }
}
