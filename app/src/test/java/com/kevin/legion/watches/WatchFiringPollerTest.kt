package com.kevin.legion.watches

import com.kevin.legion.watches.FakeWatchesBackend.Companion.firing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR 0083: what the engine's watches found is said once, several at a time as ONE line, acked with
 * how it was delivered, and never repeated when an ack fails. Made-up facts only.
 */
class WatchFiringPollerTest {

    private var now = 1_000_000L
    private val backend = FakeWatchesBackend()
    private val said = MapSaidFirings()
    private val announced = mutableListOf<Pair<List<WatchFiring>, Int>>()
    private var delivery = WatchDelivery.SPOKEN

    private val poller = WatchFiringPoller(
        controller = { WatchesController(backend) },
        announce = { firings, more ->
            announced += firings to more
            delivery
        },
        said = said,
        clockMs = { now },
    )

    @Test
    fun `several firings are one line, the rest counted, and all acked once`() = runBlocking {
        backend.pendingFirings += (1..5).map { firing("f$it", "Fact $it.") }
        assertEquals(3, poller.maybePoll())
        assertEquals(1, announced.size)
        assertEquals(listOf("f1", "f2", "f3"), announced[0].first.map { it.id })
        assertEquals(2, announced[0].second)
        assertEquals(listOf(listOf("f1", "f2", "f3", "f4", "f5") to OUTCOME_SPOKEN), backend.acks)
        now += WatchFiringPoller.POLL_INTERVAL_MS
        assertEquals(0, poller.maybePoll())
        assertEquals(1, announced.size)
    }

    @Test
    fun `it asks the engine at most once per interval`() = runBlocking {
        poller.maybePoll()
        backend.pendingFirings += firing("f1", "Fact.")
        now += WatchFiringPoller.POLL_INTERVAL_MS - 1
        assertEquals(0, poller.maybePoll())
        now += 1
        assertEquals(1, poller.maybePoll())
    }

    @Test
    fun `not now acks nothing so the next poll tries again`() = runBlocking {
        backend.pendingFirings += firing("f1", "Fact.")
        delivery = WatchDelivery.RETRY
        poller.maybePoll()
        assertTrue(backend.acks.isEmpty())
        delivery = WatchDelivery.NOTIFIED
        now += WatchFiringPoller.POLL_INTERVAL_MS
        poller.maybePoll()
        assertEquals(listOf(listOf("f1") to OUTCOME_NOTIFIED), backend.acks)
        assertEquals(2, announced.size)
    }

    @Test
    fun `a failed ack never makes it say the same thing twice`() = runBlocking {
        backend.pendingFirings += firing("f1", "Fact.")
        backend.ackFails = true
        poller.maybePoll()
        backend.ackFails = false
        now += WatchFiringPoller.POLL_INTERVAL_MS
        poller.maybePoll()
        assertEquals(1, announced.size)
        assertEquals(listOf(listOf("f1") to OUTCOME_SPOKEN), backend.acks)
        assertTrue(said.held.isEmpty())
    }

    @Test
    fun `switched off is acked silenced, so nothing bursts out later`() = runBlocking {
        backend.pendingFirings += firing("f1", "Fact.")
        delivery = WatchDelivery.SILENCED
        assertEquals(0, poller.maybePoll())
        assertEquals(listOf(listOf("f1") to OUTCOME_SILENCED), backend.acks)
    }

    @Test
    fun `an expired firing is not said`() = runBlocking {
        backend.pendingFirings += firing("f1", "Due an hour ago.", expiresAtMs = now - 1)
        assertEquals(0, poller.maybePoll())
        assertTrue(announced.isEmpty())
    }

    @Test
    fun `offline says nothing and acks nothing`() = runBlocking {
        backend.offline = WatchOutcome.Unreachable("The engine could not be reached.")
        assertEquals(0, poller.maybePoll())
        assertTrue(announced.isEmpty() && backend.acks.isEmpty())
    }
}
