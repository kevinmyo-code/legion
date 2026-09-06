package com.kevin.legion.backend.engine

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineTransport.transportFor]'s shipped defaults, and the guard on them.
 *
 * **These tests were rewritten on 2026-09-06 when the default flipped.** They used to assert
 * "defaults to SUPABASE for an aspect never touched", naming `events` as the example, per ADR 0044
 * Phase 2's "Default supabase". Phase 3's six checks passed on the A25, so `events` and
 * `checklists` now default to [Transport.DJANGO] - **but only on a device that holds an engine
 * address and a token**, since a Django aspect with no token resolves to no backend at all. The
 * old assertion therefore still holds for a signed-OUT device, which is what most of this file
 * now pins, and the signed-IN case is the new behaviour.
 */
@RunWith(RobolectricTestRunner::class)
class EngineTransportTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Robolectric carries SharedPreferences across the tests in one class (the same trap
     * `EngineBackendsTest` documents), so an explicit flip left behind by one case would silently
     * become the "never touched" state of the next - which is exactly the state every default
     * assertion below depends on. Cleared by prefs-file name because storing a value is the only
     * thing [EngineTransport] can do to that file; there is no un-set. */
    @Before
    fun clearStoredTransports() {
        context.getSharedPreferences("engine_transport", Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** No address, no token - a fresh install that has never seen an engine. Built with the same
     * reversible fake crypto [EngineTestSupport.signedInConfig] uses, since there is no Android
     * Keystore in a JVM test. */
    private fun signedOutConfig() = EngineConfig(
        context = context,
        encrypt = { plain -> "ENC($plain)" },
        decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
    ).also { it.clearSession() }

    private fun signedOut() = EngineTransport(context, signedOutConfig())

    private fun signedIn() = EngineTransport(context, EngineTestSupport.signedInConfig(context))

    // ------------------------------------------------------------------ the guard: no engine

    @Test
    fun `with no engine on this device, events and checklists still default to SUPABASE`() {
        val transport = signedOut()

        assertEquals(Transport.SUPABASE, transport.transportFor("events"))
        assertEquals(Transport.SUPABASE, transport.transportFor("checklists"))
    }

    @Test
    fun `with no engine on this device, the fallback is reported rather than silent`() {
        val transport = signedOut()

        assertTrue(transport.isFallingBackToSupabase("events"))
        assertTrue(transport.isFallingBackToSupabase("checklists"))
        // Never on an aspect whose default was Supabase all along - there is nothing to report.
        assertFalse(transport.isFallingBackToSupabase("ledger"))
    }

    // ------------------------------------------------------------------ the flip: engine present

    @Test
    fun `signed in to an engine, events and checklists default to DJANGO`() {
        val transport = signedIn()

        assertEquals(Transport.DJANGO, transport.transportFor("events"))
        assertEquals(Transport.DJANGO, transport.transportFor("checklists"))
        assertFalse(transport.isFallingBackToSupabase("events"))
        assertFalse(transport.isFallingBackToSupabase("checklists"))
    }

    @Test
    fun `the other seven aspects stay on SUPABASE even with an engine signed in`() {
        val transport = signedIn()

        val untouched = EngineTransport.KNOWN_ASPECTS - EngineTransport.DJANGO_BY_DEFAULT
        assertEquals(7, untouched.size)
        for (aspect in untouched) {
            assertEquals("$aspect must not have moved", Transport.SUPABASE, transport.transportFor(aspect))
        }
    }

    @Test
    fun `transportFor defaults to SUPABASE for an aspect this class has never heard of`() {
        assertEquals(
            Transport.SUPABASE,
            signedIn().transportFor("some-future-aspect-nobody-named-yet"),
        )
    }

    // ------------------------------------------------------------------ an explicit choice wins

    @Test
    fun `setTransport flips one aspect without touching another`() {
        val transport = signedOut()
        transport.setTransport("ledger", Transport.DJANGO)

        assertEquals(Transport.DJANGO, transport.transportFor("ledger"))
        assertEquals(Transport.SUPABASE, transport.transportFor("pantry"))
    }

    @Test
    fun `setTransport back to SUPABASE round-trips`() {
        val transport = signedOut()
        transport.setTransport("pantry", Transport.DJANGO)
        transport.setTransport("pantry", Transport.SUPABASE)

        assertEquals(Transport.SUPABASE, transport.transportFor("pantry"))
    }

    @Test
    fun `an explicit DJANGO is honoured with no token, and is never reported as a fallback`() {
        // The fallback covers the DEFAULT only. An aspect flipped by hand resolves to Django and
        // therefore to no backend (EngineBackendsTest pins that half), rather than having its
        // writes quietly re-pointed at Supabase behind the driver's back - which is the split
        // brain the whole switch exists to prevent.
        val transport = signedOut()
        transport.setTransport("events", Transport.DJANGO)

        assertEquals(Transport.DJANGO, transport.transportFor("events"))
        assertFalse(transport.isFallingBackToSupabase("events"))
    }

    @Test
    fun `an explicit SUPABASE on a signed-in device is honoured over the Django default`() {
        val transport = signedIn()
        transport.setTransport("events", Transport.SUPABASE)

        assertEquals(Transport.SUPABASE, transport.transportFor("events"))
        // Explicit, not a fallback - so there is nothing for SYNC NOW to explain.
        assertFalse(transport.isFallingBackToSupabase("events"))
    }
}
