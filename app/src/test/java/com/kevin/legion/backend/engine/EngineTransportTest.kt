package com.kevin.legion.backend.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineTransport.transportFor] must default to [Transport.SUPABASE] for anything not explicitly
 * flipped - including an aspect name this class has never heard of - per ADR 0044 Phase 2's
 * "Default supabase" and the ticket brief's own verification list. Nothing in the app reads this
 * yet (see the class doc); these tests are the seam's own contract, not an integration check.
 */
@RunWith(RobolectricTestRunner::class)
class EngineTransportTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `transportFor defaults to SUPABASE for an aspect never touched`() {
        val transport = EngineTransport(context)
        assertEquals(Transport.SUPABASE, transport.transportFor("events"))
    }

    @Test
    fun `transportFor defaults to SUPABASE for an aspect this class has never heard of`() {
        val transport = EngineTransport(context)
        assertEquals(Transport.SUPABASE, transport.transportFor("some-future-aspect-nobody-named-yet"))
    }

    @Test
    fun `setTransport flips one aspect without touching another`() {
        val transport = EngineTransport(context)
        transport.setTransport("events", Transport.DJANGO)

        assertEquals(Transport.DJANGO, transport.transportFor("events"))
        assertEquals(Transport.SUPABASE, transport.transportFor("checklists"))
    }

    @Test
    fun `setTransport back to SUPABASE round-trips`() {
        val transport = EngineTransport(context)
        transport.setTransport("ledger", Transport.DJANGO)
        transport.setTransport("ledger", Transport.SUPABASE)

        assertEquals(Transport.SUPABASE, transport.transportFor("ledger"))
    }
}
