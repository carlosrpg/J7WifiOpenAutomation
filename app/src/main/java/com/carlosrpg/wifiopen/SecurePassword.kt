package com.carlosrpg.wifiopen

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the EngMode encryption password at rest with AES-256-GCM, under a key held in the Android
 * Keystore (hardware-backed where available, never extractable). The plaintext exists only transiently
 * in memory while a run types it; it is never written to disk, logged, or shown outside its input field.
 */
object SecurePassword {
    private const val TAG = "J7WifiOpen"
    private const val ALIAS = "adb_pw_key"
    private const val PREF_KEY = "adb_password_enc" // Base64 of iv(12) + ciphertext+tag
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** @return the stored password, or null if none is set (or decryption failed, e.g. after a key wipe). */
    fun load(prefs: SharedPreferences): String? {
        val blob = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ct = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt stored password: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Encrypts and stores the password. An empty value clears it. */
    fun save(prefs: SharedPreferences, password: String) {
        if (password.isEmpty()) {
            prefs.edit().remove(PREF_KEY).apply()
            return
        }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ct = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            val blob = Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
            prefs.edit().putString(PREF_KEY, blob).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not encrypt password: ${e.javaClass.simpleName}")
        }
    }

    fun isSet(prefs: SharedPreferences): Boolean = prefs.contains(PREF_KEY)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}
