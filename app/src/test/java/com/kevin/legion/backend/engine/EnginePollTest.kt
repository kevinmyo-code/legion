package com.kevin.legion.backend.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EnginePoll] - the 60 s foreground poll that stands in for Supabase Realtime on a Django aspect.
 *
 * **The property this exists to prove is a negative: the poll does not run while an aspect is on
 * Supabase.** That matters because Realtime is still subscribed for such an aspect, and two live
 * mechanisms pulling the same table would double every round trip and race each other's merges.
 * Asserted at the level that actually matters - nothing is PULLED - rather than by inspecting a
 * flag, since a poll that starts and pulls nothing and a poll that never starts are the same thing
 * to the server and to the battery.
 */
@RunWith(RobolectricTestRunner::class)
class EnginePollTest {

    private val context = RuntimeEnvironment.getApplication()

    private var eventPulls = 0
    private var checklistPulls = 0

    private fun poll() = EnginePoll(
        transport = EngineTransport(context),
        pullEvents = { eventPulls++ },
        pullChecklists = { checklistPulls++ },
    )

    @Before
    fun setUp() {
        eventPulls = 0
        checklistPulls = 0
        val transport = EngineTransport(context)
        EngineTransport.KNOWN_ASPECTS.forEach { transport.setTransport(it, Transport.SUPABASE) }
    }

    @Test
    fun `the poll does not start, and pulls nothing, while every aspect is on Supabase`() = runBlocking {
        val poll = poll()

        assertFalse(poll.shouldPoll())
        poll.pollOnce()

        assertEquals(0, eventPulls)
        assertEquals(0, checklistPulls)
    }

    @Test
    fun `one aspect on Django starts the poll, and only that aspect is pulled`() = runBlocking {
        EngineTransport(context).setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)
        val poll = poll()

        assertTrue(poll.shouldPoll())
        poll.pollOnce()

        assertEquals(1, eventPulls)
        // checklists is still on Supabase - and has no Supabase backend at all, so pulling it here
        // would be pulling from a transport it is not on.
        assertEquals(0, checklistPulls)
    }

    @Test
    fun `both aspects on Django pulls both`() = runBlocking {
        val transport = EngineTransport(context)
        transport.setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)
        transport.setTransport(EngineBackends.ASPECT_CHECKLISTS, Transport.DJANGO)
        val poll = poll()

        poll.pollOnce()

        assertEquals(1, eventPulls)
        assertEquals(1, checklistPulls)
    }

    @Test
    fun `flipping an aspect back to Supabase stops it being polled, with no restart`() = runBlocking {
        val transport = EngineTransport(context)
        transport.setTransport(EngineBackends.ASPECT_EVENTS, Transport.DJANGO)
        val poll = poll()
        poll.pollOnce()
        assertEquals(1, eventPulls)

        transport.setTransport(EngineBackends.ASPECT_EVENTS, Transport.SUPABASE)
        poll.pollOnce()

        // Read on every tick, not cached at construction - so the debug transport row takes effect
        // within one interval rather than needing the process restarted.
        assertEquals(1, eventPulls)
        assertFalse(poll.shouldPoll())
    }
}
