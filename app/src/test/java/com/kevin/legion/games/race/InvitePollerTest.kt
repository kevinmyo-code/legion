package com.kevin.legion.games.race

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InvitePollerTest {
    private class MemorySeen(var ids: Set<String> = emptySet()) : InviteSeenStore {
        override fun load() = ids
        override fun save(ids: Set<String>) {
            this.ids = ids
        }
    }

    private val backend = FakeGamesBackend()
    private val seen = MemorySeen()
    private val raised = mutableListOf<PendingInvite>()
    private val retracted = mutableListOf<String>()
    private var engine: GamesBackend? = backend

    private val poller = InvitePoller(
        backend = { engine },
        seen = seen,
        raise = { raised += it },
        retract = { retracted += it },
    )

    @Test
    fun aNewInviteRaisesOnceAcrossRepeatedPolls() = runBlocking {
        backend.pendingResult = Result.success(listOf(PendingInvite("a", "Mia")))
        assertEquals(PollOutcome.Polled(raised = 1, retracted = 0), poller.pollOnce())
        assertEquals(PollOutcome.Polled(raised = 0, retracted = 0), poller.pollOnce())
        assertEquals(PollOutcome.Polled(raised = 0, retracted = 0), poller.pollOnce())
        assertEquals(listOf(PendingInvite("a", "Mia")), raised)
    }

    @Test
    fun aSecondInviteRaisesWithoutRepeatingTheFirst() = runBlocking {
        backend.pendingResult = Result.success(listOf(PendingInvite("a", "Mia")))
        poller.pollOnce()
        backend.pendingResult = Result.success(listOf(PendingInvite("a", "Mia"), PendingInvite("b", "Mia")))
        poller.pollOnce()
        assertEquals(listOf("a", "b"), raised.map { it.matchId })
    }

    @Test
    fun anInviteThatStopsBeingPendingIsRetracted() = runBlocking {
        backend.pendingResult = Result.success(listOf(PendingInvite("a", "Mia")))
        poller.pollOnce()
        backend.pendingResult = Result.success(emptyList())
        assertEquals(PollOutcome.Polled(raised = 0, retracted = 1), poller.pollOnce())
        assertEquals(listOf("a"), retracted)
    }

    @Test
    fun aFailedPollRaisesNothingAndKeepsTheSeenSet() = runBlocking {
        backend.pendingResult = Result.success(listOf(PendingInvite("a", "Mia")))
        poller.pollOnce()
        backend.pendingResult = Result.failure(IllegalStateException("The engine is down."))
        val outcome = poller.pollOnce()
        assertTrue(outcome is PollOutcome.Failed)
        assertEquals("The engine is down.", (outcome as PollOutcome.Failed).sentence)
        assertEquals(setOf("a"), seen.ids)
        assertTrue(retracted.isEmpty())
        // When it comes back with the same invite, it is not raised a second time.
        backend.pendingResult = Result.success(listOf(PendingInvite("a", "Mia")))
        poller.pollOnce()
        assertEquals(1, raised.size)
    }

    @Test
    fun noEngineMeansNothingIsAskedOrRaised() = runBlocking {
        engine = null
        assertEquals(PollOutcome.NoEngine, poller.pollOnce())
        assertTrue(raised.isEmpty())
    }
}
