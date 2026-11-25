package com.example

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.auth.KeystoreEncryptedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityConfigTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(KeystoreEncryptedSettings.ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(KeystoreEncryptedSettings.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun testKeystoreEncryptedSettingsEncryptsValuesAndMigratesLegacyPlaintext() {
        val secretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val legacyPrefs = context.getSharedPreferences(KeystoreEncryptedSettings.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putString("session_json", "{\"access_token\":\"secret_jwt_123\"}")
            .putInt("expires_in", 3600)
            .putBoolean("remember_me", true)
            .commit()

        val encryptedSettings = KeystoreEncryptedSettings(
            context = context,
            keyProvider = { secretKey }
        )

        // Legacy entries must be decrypted transparently via new settings
        assertEquals("{\"access_token\":\"secret_jwt_123\"}", encryptedSettings.getStringOrNull("session_json"))
        assertEquals(3600, encryptedSettings.getInt("expires_in", -1))
        assertTrue(encryptedSettings.getBoolean("remember_me", false))

        // Legacy plaintext preferences must be cleared
        assertTrue(legacyPrefs.all.isEmpty())

        // Underlying stored value in SharedPreferences must NOT contain plaintext token
        val rawStored = context.getSharedPreferences(KeystoreEncryptedSettings.ENCRYPTED_PREFS_NAME, Context.MODE_PRIVATE)
            .getString("session_json", null)
        assertNotNull(rawStored)
        assertNotEquals("{\"access_token\":\"secret_jwt_123\"}", rawStored)
        assertFalse(rawStored!!.contains("secret_jwt_123"))

        // Round-trip typed reads/writes
        encryptedSettings.putLong("saved_at", 1700000000L)
        encryptedSettings.putDouble("version", 2.5)
        assertEquals(1700000000L, encryptedSettings.getLong("saved_at", 0L))
        assertEquals(2.5, encryptedSettings.getDouble("version", 0.0), 0.001)

        encryptedSettings.remove("saved_at")
        assertNull(encryptedSettings.getLongOrNull("saved_at"))
    }

    @Test
    fun testManifestDisablesCleartextTrafficAndConfiguresNetworkSecurity() {
        val appInfo = context.applicationInfo
        val usesCleartext = (appInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
        assertFalse("Manifest must disable cleartext traffic", usesCleartext)

        val resId = context.resources.getIdentifier("network_security_config", "xml", context.packageName)
        assertTrue("network_security_config.xml resource must exist", resId != 0)
    }
}
