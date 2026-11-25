package com.example.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.russhwolf.settings.Settings
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted [Settings] implementation backed by Android Keystore AES-256-GCM.
 *
 * All values (tokens, session JSON, code verifiers) are encrypted with a hardware/Keystore-backed
 * 256-bit AES-GCM key before being written to SharedPreferences. Performs a one-time migration from
 * legacy unencrypted [legacyPrefsName] and clears the plaintext entries afterward.
 */
class KeystoreEncryptedSettings(
    context: Context,
    prefsName: String = ENCRYPTED_PREFS_NAME,
    legacyPrefsName: String? = LEGACY_PREFS_NAME,
    private val keyProvider: SecretKeyProvider = AndroidKeystoreSecretKeyProvider()
) : Settings {

    fun interface SecretKeyProvider {
        fun getOrCreateSecretKey(): SecretKey
    }

    private val encryptedPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        if (!legacyPrefsName.isNullOrBlank() && legacyPrefsName != prefsName) {
            migrateLegacyPreferences(context.applicationContext, legacyPrefsName)
        }
    }

    private fun migrateLegacyPreferences(appContext: Context, legacyPrefsName: String) {
        try {
            val legacyPrefs = appContext.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)
            val allEntries = legacyPrefs.all
            if (allEntries.isEmpty()) return

            for ((key, value) in allEntries) {
                if (!encryptedPrefs.contains(key)) {
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                    }
                }
            }
            legacyPrefs.edit().clear().apply()
        } catch (t: Throwable) {
            Log.e(TAG, "Legacy session migration error", t)
        }
    }

    private fun encryptTagged(taggedValue: String): String {
        val secretKey = keyProvider.getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(taggedValue.toByteArray(Charsets.UTF_8))

        val payload = ByteBuffer.allocate(1 + iv.size + ciphertext.size)
            .put(iv.size.toByte())
            .put(iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decryptTagged(encoded: String): String? {
        return try {
            val raw = Base64.decode(encoded, Base64.NO_WRAP)
            if (raw.size < 2) return null
            val ivLength = raw[0].toInt() and 0xFF
            if (ivLength <= 0 || raw.size < 1 + ivLength) return null

            val iv = raw.copyOfRange(1, 1 + ivLength)
            val ciphertext = raw.copyOfRange(1 + ivLength, raw.size)

            val secretKey = keyProvider.getOrCreateSecretKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to decrypt preference value", t)
            null
        }
    }

    private fun writeTagged(key: String, tag: Char, rawValue: String) {
        val encrypted = encryptTagged("$tag:$rawValue")
        encryptedPrefs.edit().putString(key, encrypted).apply()
    }

    private fun readTagged(key: String, expectedTag: Char): String? {
        val stored = encryptedPrefs.getString(key, null) ?: return null
        val decrypted = decryptTagged(stored) ?: return null
        if (decrypted.length < 2 || decrypted[0] != expectedTag || decrypted[1] != ':') {
            return null
        }
        return decrypted.substring(2)
    }

    override val keys: Set<String>
        get() = encryptedPrefs.all.keys

    override val size: Int
        get() = encryptedPrefs.all.size

    override fun clear() {
        encryptedPrefs.edit().clear().apply()
    }

    override fun remove(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }

    override fun hasKey(key: String): Boolean = encryptedPrefs.contains(key)

    override fun putInt(key: String, value: Int) = writeTagged(key, TAG_INT, value.toString())

    override fun getInt(key: String, defaultValue: Int): Int =
        readTagged(key, TAG_INT)?.toIntOrNull() ?: defaultValue

    override fun getIntOrNull(key: String): Int? =
        readTagged(key, TAG_INT)?.toIntOrNull()

    override fun putLong(key: String, value: Long) = writeTagged(key, TAG_LONG, value.toString())

    override fun getLong(key: String, defaultValue: Long): Long =
        readTagged(key, TAG_LONG)?.toLongOrNull() ?: defaultValue

    override fun getLongOrNull(key: String): Long? =
        readTagged(key, TAG_LONG)?.toLongOrNull()

    override fun putString(key: String, value: String) = writeTagged(key, TAG_STRING, value)

    override fun getString(key: String, defaultValue: String): String =
        readTagged(key, TAG_STRING) ?: defaultValue

    override fun getStringOrNull(key: String): String? =
        readTagged(key, TAG_STRING)

    override fun putFloat(key: String, value: Float) = writeTagged(key, TAG_FLOAT, value.toString())

    override fun getFloat(key: String, defaultValue: Float): Float =
        readTagged(key, TAG_FLOAT)?.toFloatOrNull() ?: defaultValue

    override fun getFloatOrNull(key: String): Float? =
        readTagged(key, TAG_FLOAT)?.toFloatOrNull()

    override fun putDouble(key: String, value: Double) = writeTagged(key, TAG_DOUBLE, value.toString())

    override fun getDouble(key: String, defaultValue: Double): Double =
        readTagged(key, TAG_DOUBLE)?.toDoubleOrNull() ?: defaultValue

    override fun getDoubleOrNull(key: String): Double? =
        readTagged(key, TAG_DOUBLE)?.toDoubleOrNull()

    override fun putBoolean(key: String, value: Boolean) = writeTagged(key, TAG_BOOLEAN, value.toString())

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        readTagged(key, TAG_BOOLEAN)?.toBooleanStrictOrNull() ?: defaultValue

    override fun getBooleanOrNull(key: String): Boolean? =
        readTagged(key, TAG_BOOLEAN)?.toBooleanStrictOrNull()

    private class AndroidKeystoreSecretKeyProvider : SecretKeyProvider {
        @Synchronized
        override fun getOrCreateSecretKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
    }

    companion object {
        private const val TAG = "KeystoreEncSettings"
        const val ENCRYPTED_PREFS_NAME = "supabase_session_enc"
        const val LEGACY_PREFS_NAME = "supabase_session"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "midar_session_aes_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        private const val TAG_STRING = 's'
        private const val TAG_INT = 'i'
        private const val TAG_LONG = 'l'
        private const val TAG_FLOAT = 'f'
        private const val TAG_DOUBLE = 'd'
        private const val TAG_BOOLEAN = 'b'
    }
}
