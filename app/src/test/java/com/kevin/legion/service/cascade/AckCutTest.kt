package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acknowledgement's cut, as behaviour (2026-11-21, review of c4e79a40): a cancelled acknowledgement
 * followed at once by a farewell. The farewell's speaker takes the player's generation as the session's
 * does (when it is made), and its writes must be accepted and played. A stand-in player with the
 * real generation rule: a flush moves the generation, and a write under an older one is refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AckCutTest {
    private class GenPlayer {
        var generation = 0
            private set
        var played = 0
            private set
        var flushes = 0
            private set

        fun flush() {
            generation++
            flushes++
        }

        fun write(frames: Int, gen: Int): Boolean {
            if (gen != generation) return false
            played += frames
            return true
        }
    }

    @Test
    fun `a cancelled acknowledgement is cut before the farewell is queued, and the farewell plays`() = runTest {
        val p = GenPlayer()
        val started = CompletableDeferred<Unit>()
        lateinit var cut: AckCut
        val ack = launch {
            cut.playing = true
            try {
                p.write(7_776, p.generation)
                started.complete(Unit)
                awaitCancellation() // it waits for its drain, holding the gate
            } finally {
                cut.playing = false // the clip itself never flushes
            }
        }
        cut = AckCut(endJobs = { ack.cancel() }, flush = p::flush)
        runCurrent()
        started.await()
        // The goodbye: the session cuts on the loop, then makes the farewell's speaker at once.
        cut.cut()
        val farewellGen = p.generation
        runCurrent() // the cancelled clip finishes after: it must not flush again
        assertTrue(p.write(21_600, farewellGen))
        assertEquals(1, p.flushes)
        assertEquals(7_776 + 21_600, p.played)
        assertFalse(cut.playing)
    }

    @Test
    fun `the old order, a flush from the cancelled clip after the farewell took its generation, drops the farewell`() {
        val p = GenPlayer()
        val farewellGen = p.generation // the speaker was made before the clip's late flush
        p.flush()
        assertFalse(p.write(21_600, farewellGen))
    }

    @Test
    fun `no acknowledgement playing, nothing is flushed`() {
        val p = GenPlayer()
        var ended = 0
        AckCut(endJobs = { ended++ }, flush = p::flush).cut()
        assertEquals(1, ended)
        assertEquals(0, p.flushes)
    }
}
