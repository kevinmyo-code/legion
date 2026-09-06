package com.kevin.legion.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineConfig]'s storage contract - base URL validation/trimming, and the
 * [EngineConfig.saveSession] fail-closed branch, exercised through injected [encrypt]/[decrypt]
 * lambdas rather than a real Android Keystore (same seam [com.kevin.legion.backend.SupabaseSession]
 * already established for the identical reason - see that class's own test suite).
 *
 * Robolectric only because the constructor takes a real [android.content.Context] for
 * [android.content.SharedPreferences] - no Keystore path is exercised here, matching
 * `SupabaseAuthTest`'s own doc comment about why it needs Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class EngineConfigTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Round-trips through a trivial reversible transform rather than real AES, so a test can
     * assert the STORED value is not the plaintext token without needing real crypto. */
    private fun fakeConfig(): EngineConfig = EngineConfig(
        context = context,
        encrypt = { plain -> "ENC($plain)" },
        decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
    )

    @Test
    fun `saveBaseUrl accepts http and https and rejects everything else`() {
        val config = fakeConfig()
        assertTrue(config.saveBaseUrl("http://192.168.1.20:8000"))
        assertEquals("http://192.168.1.20:8000", config.baseUrl())

        assertFalse(config.saveBaseUrl("ftp://example.com"))
        assertFalse(config.saveBaseUrl(""))
        assertFalse(config.saveBaseUrl("192.168.1.20:8000"))
    }

    @Test
    fun `saveBaseUrl trims a trailing slash so a caller can always append a leading-slash path`() {
        val config = fakeConfig()
        assertTrue(config.saveBaseUrl("https://engine.example.com/"))
        assertEquals("https://engine.example.com", config.baseUrl())
    }

    @Test
    fun `saveSession stores the token only through encrypt, never as plaintext`() {
        val config = fakeConfig()
        assertTrue(config.saveSession("raw-device-token", "user-123"))

        assertEquals("raw-device-token", config.token())
        assertEquals("user-123", config.userId())
        assertTrue(config.isSignedIn())

        // Read the underlying prefs value directly (bypassing EngineConfig.token()'s own
        // decrypt call) to confirm the plaintext token itself was never written to disk.
        val rawStored = context.applicationContext
            .getSharedPreferences("engine_config", android.content.Context.MODE_PRIVATE)
            .getString("device_token", null)
        assertEquals("ENC(raw-device-token)", rawStored)
    }

    @Test
    fun `saveSession fails closed when encrypt refuses - nothing is stored, not even the user id`() {
        val config = EngineConfig(
            context = context,
            encrypt = { null }, // simulates a broken Keystore, matching KeyVault.encrypt's null-on-failure contract
            decrypt = { null },
        )

        val saved = config.saveSession("raw-device-token", "user-123")

        assertFalse("a broken Keystore must refuse the save, not fall back to plaintext", saved)
        assertNull(config.token())
        assertNull(config.userId())
        assertFalse(config.isSignedIn())
    }

    @Test
    fun `clearSession removes the token and user id but leaves the base url alone`() {
        val config = fakeConfig()
        config.saveBaseUrl("http://192.168.1.20:8000")
        config.saveSession("raw-device-token", "user-123")

        config.clearSession()

        assertNull(config.token())
        assertNull(config.userId())
        assertFalse(config.isSignedIn())
        assertEquals("http://192.168.1.20:8000", config.baseUrl())
    }
}
