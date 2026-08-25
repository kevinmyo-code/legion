package com.kevin.legion.backend

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [SupabaseSession]'s round trip and its fail-closed departure from every other BYO secret slot
 * in this codebase (ticket 02 ruling 5 - see the class's own doc comment). [encrypt]/[decrypt] are
 * injected here rather than exercised through the real [com.kevin.legion.ai.KeyVault], which
 * cannot run under Robolectric (`AndroidKeyStore` has no shadow) - the fail-closed behaviour is
 * exactly what fires whenever KeyVault genuinely fails on a real device, so simulating that via
 * the injected seam is testing the real failure mode, not an artifact of the test environment.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlin.time.ExperimentalTime::class)
class SupabaseSessionTest {
    private val context = RuntimeEnvironment.getApplication()

    // A trivial reversible "cipher" standing in for KeyVault's real AES/GCM - the point of these
    // tests is SupabaseSession's own logic, not cryptography.
    private val identityEncrypt: (String) -> String? = { it.reversed() }
    private val identityDecrypt: (String) -> String? = { it.reversed() }

    private fun sampleSession() = UserSession(
        accessToken = "access-token-abc",
        refreshToken = "refresh-token-xyz",
        expiresIn = 3600,
        tokenType = "bearer",
    )

    @Before
    fun clearState() {
        context.getSharedPreferences("supabase_session", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `save then load round trips the session`() = runBlocking {
        val session = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)

        session.saveSession(sampleSession())
        val loaded = session.loadSession()

        assertEquals("access-token-abc", loaded.accessToken)
        assertEquals("refresh-token-xyz", loaded.refreshToken)
        assertEquals(3600L, loaded.expiresIn)
        assertEquals("bearer", loaded.tokenType)
        assertTrue(session.lastSaveSucceeded)
    }

    @Test
    fun `loadSession throws when nothing was ever saved`() = runBlocking {
        val session = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)

        try {
            session.loadSession()
            fail("expected NoSupabaseSessionException")
        } catch (e: NoSupabaseSessionException) {
            // expected - the documented "normal, expected logged out state"
        }
    }

    @Test
    fun `loadSessionOrNull returns null instead of throwing`() = runBlocking {
        val session = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)

        assertNull(session.loadSessionOrNull())
    }

    @Test
    fun `deleteSession clears a saved session`() = runBlocking {
        val session = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)
        session.saveSession(sampleSession())

        session.deleteSession()

        try {
            session.loadSession()
            fail("expected NoSupabaseSessionException after delete")
        } catch (e: NoSupabaseSessionException) {
            // expected
        }
    }

    // --- The fail-closed case (ticket 02 ruling 5): the whole point of this class. ---

    @Test
    fun `when encryption fails saveSession writes nothing and throws`() = runBlocking {
        val failingEncrypt: (String) -> String? = { null }
        val session = SupabaseSession(context, encrypt = failingEncrypt, decrypt = identityDecrypt)

        try {
            session.saveSession(sampleSession())
            fail("expected SessionPersistFailedException")
        } catch (e: SessionPersistFailedException) {
            // expected - message says in words what did not happen
            assertTrue(e.message.orEmpty().contains("not"))
        }

        assertFalse(session.lastSaveSucceeded)
        // Nothing was written - a fresh SupabaseSession reading the same prefs sees no session.
        val reader = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)
        assertNull(reader.loadSessionOrNull())
    }

    @Test
    fun `a failed save does not clobber a previously persisted session`() = runBlocking {
        val session = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)
        session.saveSession(sampleSession())

        val failingEncrypt: (String) -> String? = { null }
        val flakySession = SupabaseSession(context, encrypt = failingEncrypt, decrypt = identityDecrypt)
        try {
            flakySession.saveSession(sampleSession().copy(accessToken = "different-token"))
            fail("expected SessionPersistFailedException")
        } catch (e: SessionPersistFailedException) {
            // expected
        }

        // The FIRST session (saved while encryption was working) is still readable - a
        // transient encryption failure on a later save must not corrupt or drop the last-good one.
        val reader = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)
        assertEquals("access-token-abc", reader.loadSession().accessToken)
    }

    @Test
    fun `a corrupt blob is treated as no session rather than crashing`() = runBlocking {
        context.getSharedPreferences("supabase_session", android.content.Context.MODE_PRIVATE)
            .edit().putString("session_enc", "not valid json reversed".reversed()).apply()
        val session = SupabaseSession(context, encrypt = identityEncrypt, decrypt = identityDecrypt)

        assertNull(session.loadSessionOrNull())
    }
}
