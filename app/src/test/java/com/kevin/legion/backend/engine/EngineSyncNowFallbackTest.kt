package com.kevin.legion.backend.engine

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The SYNC NOW row's clause for the 2026-09-06 default flip's guard.
 *
 * `events` and `checklists` default to [Transport.DJANGO] now, but only on a device that holds an
 * engine address and a token; without one they fall back to Supabase rather than resolving to no
 * backend at all. **A fallback nobody is told about is indistinguishable from a setting that never
 * took effect**, and the Setup screen is the one surface that answers "is this phone actually
 * talking to the engine" in words - so the fallback is said there.
 */
@RunWith(RobolectricTestRunner::class)
class EngineSyncNowFallbackTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun signedOutConfig() = EngineConfig(
        context = context,
        encrypt = { plain -> "ENC($plain)" },
        decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
    ).also { it.clearSession() }

    /** Robolectric carries SharedPreferences across the tests in one class, and every case here
     * depends on nothing having been flipped by hand. */
    @Before
    fun clearStoredTransports() {
        context.getSharedPreferences("engine_transport", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun `with no engine signed in, both slice aspects say why they are on Supabase`() {
        val config = signedOutConfig()
        val syncNow = EngineSyncNow(context, EngineBackends(context, config))

        val expected = " Django is this aspect's default now, but this device is not signed in " +
            "to an engine, so it is on Supabase."
        assertEquals(expected, syncNow.fallbackNote(EngineBackends.ASPECT_EVENTS))
        assertEquals(expected, syncNow.fallbackNote(EngineBackends.ASPECT_CHECKLISTS))
    }

    @Test
    fun `an aspect that was never on Django says nothing extra`() {
        val syncNow = EngineSyncNow(context, EngineBackends(context, signedOutConfig()))

        assertEquals("", syncNow.fallbackNote("ledger"))
    }

    @Test
    fun `an explicitly flipped aspect says nothing extra, because nothing fell back`() {
        // The fallback covers the shipped default only. An aspect Kevin flipped by hand is
        // honoured verbatim - it resolves to no backend and the line above the note already says
        // so - rather than having its writes quietly re-pointed at Supabase.
        val config = signedOutConfig()
        EngineTransport(context, config).setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)
        val syncNow = EngineSyncNow(context, EngineBackends(context, config))

        assertEquals("", syncNow.fallbackNote(EngineBackends.ASPECT_EVENTS))
    }

    @Test
    fun `signed in to an engine, there is nothing to explain`() {
        val config = EngineTestSupport.signedInConfig(context)
        val syncNow = EngineSyncNow(context, EngineBackends(context, config))

        assertEquals("", syncNow.fallbackNote(EngineBackends.ASPECT_EVENTS))
        assertEquals("", syncNow.fallbackNote(EngineBackends.ASPECT_CHECKLISTS))
    }
}
