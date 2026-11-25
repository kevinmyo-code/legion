package com.kevin.legion.media

import com.kevin.legion.media.TransportReadback.Move
import com.kevin.legion.media.TransportReadback.Observation
import com.kevin.legion.media.TransportReadback.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A transport command may be called done only once the player's own state says so
 * (`music-suggestions-1124` P4; CLAUDE.md section 7, ADR 0031).
 *
 * The bug this pins: a dispatch that did not throw was reported as `success: true` with no state
 * at all, so an outcome verb was spoken over a command nobody had checked. Plain JVM: the judgment
 * is pure, and the polling that feeds it lives in the callers.
 */
class TransportReadbackTest {

    @Test
    fun `play and pause are judged on the state they asked for`() {
        assertEquals(Verdict.CONFIRMED, TransportReadback.verdict(Move.PLAY, null, Observation(playing = true)))
        assertEquals(Verdict.CONTRADICTED, TransportReadback.verdict(Move.PLAY, null, Observation(playing = false)))
        assertEquals(Verdict.CONFIRMED, TransportReadback.verdict(Move.PAUSE, null, Observation(playing = false)))
        assertEquals(Verdict.CONTRADICTED, TransportReadback.verdict(Move.PAUSE, null, Observation(playing = true)))
    }

    @Test
    fun `a player that reported nothing back is never called done`() {
        // The exact shape of the bug: the command went out, nothing came back, and the old code
        // said success. "Cannot tell" is an answer; "done" is not available.
        for (m in Move.entries) {
            assertEquals(m.name, Verdict.CANNOT_TELL, TransportReadback.verdict(m, Observation(playing = true), null))
        }
    }

    @Test
    fun `a state the player did not state is unknown, never paused`() {
        // Buffering right after a play, or a session publishing no state at all, is not a failure.
        // Answering "it still reports not playing" to a play that worked is the same dishonesty in
        // the other direction.
        assertEquals(Verdict.CANNOT_TELL, TransportReadback.verdict(Move.PLAY, null, Observation(playing = null)))
        assertEquals(Verdict.CANNOT_TELL, TransportReadback.verdict(Move.PAUSE, null, Observation(playing = null)))
    }

    @Test
    fun `a skip needs a different item, not a playing flag`() {
        val before = Observation(playing = true, trackKey = "a", positionMs = 30_000)
        // The flag is unchanged by a successful skip, so it proves nothing either way.
        assertEquals(
            Verdict.CONFIRMED,
            TransportReadback.verdict(Move.NEXT, before, Observation(playing = true, trackKey = "b", positionMs = 0)),
        )
        assertEquals(
            Verdict.CONTRADICTED,
            TransportReadback.verdict(Move.NEXT, before, Observation(true, trackKey = "a", positionMs = 31_000)),
        )
    }

    @Test
    fun `previous on a track already playing is confirmed by the position moving back`() {
        val before = Observation(playing = true, trackKey = "a", positionMs = 40_000)
        val restarted = Observation(playing = true, trackKey = "a", positionMs = 0)
        assertEquals(Verdict.CONFIRMED, TransportReadback.verdict(Move.PREVIOUS, before, restarted))
    }

    @Test
    fun `a skip with neither identity nor position published cannot be judged`() {
        val before = Observation(playing = true)
        assertEquals(Verdict.CANNOT_TELL, TransportReadback.verdict(Move.NEXT, before, Observation(playing = true)))
        // And with no reading from before the dispatch there is nothing to compare against.
        assertEquals(
            Verdict.CANNOT_TELL,
            TransportReadback.verdict(Move.NEXT, null, Observation(playing = true, trackKey = "b")),
        )
    }
}
