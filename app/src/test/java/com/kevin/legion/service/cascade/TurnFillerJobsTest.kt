package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review of 69a46161 (2026-11-20). B1: a turn that ends with no answer (noise, a dropped transcript, a
 * tap stop) plays no wait line afterwards. M1: a clip cancelled mid-play leaves no playback mark.
 * Virtual time; the wait timer below is the session's own rule ([WaitFillers.nextAtMs]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnFillerJobsTest {

    /** The session's wait loop in miniature: wait lines due by [WaitFillers.nextAtMs] from his end of speech. */
    private fun TurnFillerJobs.startWaitLines(played: MutableList<Long>, now: () -> Long) = startWait {
        var said = 0
        var end = 0L
        while (true) {
            val at = WaitFillers.nextAtMs(end, said, fillersSaid = 1 + said) ?: return@startWait
            delay(at - now())
            played += now()
            said++
            end = now() + 800
            delay(800)
        }
    }

    @Test
    fun `an unanswered turn keeps its wait lines until the turn is ended`() = runTest {
        val jobs = TurnFillerJobs(this, StandardTestDispatcher(testScheduler))
        val played = mutableListOf<Long>()
        jobs.startWaitLines(played) { testScheduler.currentTime }
        advanceTimeBy(20_000)
        // Not ended: the two wait lines of a long wait (the cap holds the rest).
        assertEquals(listOf(3_500L, 8_300L), played)
        jobs.endTurn()
    }

    @Test
    fun `a noise turn, a dropped transcript or a tap stop plays no wait line afterwards`() = runTest {
        for (exit in listOf("noise", "dropped words", "tap stop")) {
            val jobs = TurnFillerJobs(this, StandardTestDispatcher(testScheduler))
            val played = mutableListOf<Long>()
            val start = testScheduler.currentTime
            jobs.startWaitLines(played) { testScheduler.currentTime - start }
            var backchannels = 0
            jobs.startBackchannel {
                delay(Backchannel.DELAY_MS)
                backchannels++
            }
            advanceTimeBy(300)
            // The turn ends with nothing to say (backToListening or interrupt).
            jobs.endTurn()
            advanceTimeBy(15_000)
            assertTrue("$exit: no wait line after it, got $played", played.isEmpty())
            assertEquals("$exit: no backchannel after it", 0, backchannels)
        }
    }

    @Test
    fun `a clip cancelled mid-play leaves no playback mark`() = runTest {
        var active = false
        val clip = ClipPlayback({ active = it }, replyHolds = { false })
        val started = CompletableDeferred<Unit>()
        val job = launch {
            clip.during {
                started.complete(Unit)
                delay(700) // the clip plays
            }
        }
        started.await()
        assertTrue(active)
        job.cancel() // his speech starts over the clip
        runCurrent()
        assertFalse("the mark is cleared when the clip is cut", active)
    }

    @Test
    fun `a clip played to its end clears the mark, unless a reply now holds playback`() = runTest {
        var active = false
        ClipPlayback({ active = it }, replyHolds = { false }).during { delay(500) }
        assertFalse(active)
        ClipPlayback({ active = it }, replyHolds = { true }).during { delay(500) }
        assertTrue("a real reply speaking keeps it", active)
    }
}
