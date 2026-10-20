package com.marketforecast.prox

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the BCS refresh token encrypted with a non-exportable Android Keystore key (AES-256/GCM).
 *
 * The token used to live in SharedPreferences as plain text. It is migrated automatically:
 * the first [get] call encrypts the legacy value and removes the plain-text copy.
 * If the Keystore is unavailable on a device, the token falls back to the old storage so the
 * app keeps working instead of locking the user out.
 */
object SecureTokenStore {
    private const val ALIAS = "mfp_bcs_token_key"
    private const val PREF_ENCRYPTED = "bcs_refresh_token_enc"
    private const val PREF_LEGACY = "bcs_refresh_token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    @Volatile private var cached: String? = null

    /** Returns the stored token, or an empty string when none is saved. */
    fun get(prefs: SharedPreferences): String {
        cached?.let { return it }
        val blob = prefs.getString(PREF_ENCRYPTED, null)
        if (!blob.isNullOrBlank()) {
            val plain = decrypt(blob)
            if (plain != null) {
                cached = plain
                return plain
            }
        }
        val legacy = prefs.getString(PREF_LEGACY, "").orEmpty()
        if (legacy.isNotBlank()) {
            set(prefs, legacy) // migrate: encrypt and remove the plain-text copy
            return legacy.trim()
        }
        return ""
    }

    /** Saves (or, for a blank value, removes) the token. */
    fun set(prefs: SharedPreferences, token: String) {
        val clean = token.trim()
        cached = clean
        val editor = prefs.edit()
        if (clean.isEmpty()) {
            editor.remove(PREF_ENCRYPTED).remove(PREF_LEGACY)
        } else {
            val blob = runCatching { encrypt(clean) }.getOrNull()
            if (blob != null) {
                editor.putString(PREF_ENCRYPTED, blob).remove(PREF_LEGACY)
            } else {
                editor.putString(PREF_LEGACY, clean).remove(PREF_ENCRYPTED)
            }
        }
        editor.apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String? = runCatching {
        val parts = blob.split(":", limit = 2)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(data), Charsets.UTF_8)
    }.getOrNull()
}
