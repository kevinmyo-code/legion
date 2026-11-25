package com.kevin.midnightai.media

import com.kevin.midnightai.media.TransportReadback.Move
import com.kevin.midnightai.media.TransportReadback.Observation
import com.kevin.midnightai.media.TransportReadback.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone transport command may be called done only once the phone session's own state says so
 * (`music-suggestions-1124` P4, mirrored from the phone app: Moose and Alfred answer the same way).
 *
 * The head unit's own Spotify branch already awaits App Remote's CallResult, so it never had this
 * defect. The PHONE branch reported success the instant the command left.
 */
class TransportReadbackTest {

    @Test
    fun `play and pause are judged on the state they asked for`() {
        assertEquals(Verdict.CONFIRMED, TransportReadback.verdict(Move.PLAY, null, Observation(playing = true)))
        assertEquals(Verdict.CONTRADICTED, TransportReadback.verdict(Move.PLAY, null, Observation(playing = false)))
        assertEquals(Verdict.CONFIRMED, TransportReadback.verdict(Move.PAUSE, null, Observation(playing = false)))
    }

    @Test
    fun `a session that reported nothing back is never called done`() {
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
        // A Bluetooth bridge reporting STATE_NONE while it plays is exactly why the flag cannot
        // decide a skip either way.
        val before = Observation(playing = true, trackKey = "a", positionMs = 30_000)
        assertEquals(
            Verdict.CONFIRMED,
            TransportReadback.verdict(Move.NEXT, before, Observation(playing = true, trackKey = "b", positionMs = 0)),
        )
        assertEquals(
            Verdict.CONTRADICTED,
            TransportReadback.verdict(Move.NEXT, before, Observation(playing = true, trackKey = "a", positionMs = 31_000)),
        )
        assertEquals(
            Verdict.CANNOT_TELL,
            TransportReadback.verdict(Move.NEXT, Observation(playing = true), Observation(playing = true)),
        )
    }

    @Test
    fun `an unconfirmed phone command says so, and does not blame an unreachable phone`() {
        val quiet = MusicRouter.unconfirmedMessage(SpotifyController.TransportCmd.NEXT, null)
        assertTrue(quiet.contains("reported nothing back"))
        assertTrue("that is a different fault from not reaching it", !quiet.contains("connected over Bluetooth"))
        val stuck = MusicRouter.unconfirmedMessage(SpotifyController.TransportCmd.PAUSE, Observation(playing = true))
        assertTrue(stuck.contains("still reports playing"))
    }
}
