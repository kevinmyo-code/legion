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
        // **Every KNOWN aspect reports it now**, `ledger` included. This line used to read
        // `assertFalse(...isFallingBackToSupabase("ledger"))` with the comment "never on an aspect
        // whose default was Supabase all along - there is nothing to report", and that was correct
        // until 2026-09-10, when all nine moved into DJANGO_BY_DEFAULT. There is no longer an
        // aspect whose default is Supabase, so there is no longer a case where falling back is
        // silent.
        for (aspect in EngineTransport.KNOWN_ASPECTS) {
            assertTrue("$aspect falls back and must say so", transport.isFallingBackToSupabase(aspect))
        }
        // An aspect this class has never heard of still reports nothing: it was never on Django,
        // so nothing fell back. That is the case the `ledger` assertion used to cover.
        assertFalse(transport.isFallingBackToSupabase("some-future-aspect-nobody-named-yet"))
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
    fun `signed in to an engine, every known aspect defaults to DJANGO`() {
        // **This test used to be `the other seven aspects stay on SUPABASE even with an engine
        // signed in`**, and it computed `KNOWN_ASPECTS - DJANGO_BY_DEFAULT` and asserted the
        // remainder was seven aspects still on Supabase. That set is empty as of 2026-09-10: all
        // nine aspects are in DJANGO_BY_DEFAULT (see its own doc comment for why the "never ahead
        // of a hardware run" rule stopped applying - the Supabase write path cannot satisfy
        // `household_id NOT NULL` and so is not a working path to protect).
        //
        // What the old test really protected was that a default is a DEFAULT and not a hardcode.
        // That property is pinned below by flipping one back rather than by counting a remainder.
        val transport = signedIn()

        assertEquals(emptyList<String>(), EngineTransport.KNOWN_ASPECTS - EngineTransport.DJANGO_BY_DEFAULT)
        for (aspect in EngineTransport.KNOWN_ASPECTS) {
            assertEquals("$aspect defaults to Django", Transport.DJANGO, transport.transportFor(aspect))
        }
    }

    @Test
    fun `a row flipped to SUPABASE is honoured even though Django is the default`() {
        // The toggle still moves an aspect BOTH ways. Now that every default is Django this is the
        // direction that can regress unnoticed, so it is the one pinned explicitly.
        val transport = signedIn()
        transport.setTransport("ledger", Transport.SUPABASE)

        assertEquals(Transport.SUPABASE, transport.transportFor("ledger"))
        assertEquals("only the flipped row moves", Transport.DJANGO, transport.transportFor("pantry"))
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
