package com.kevin.legion.media

import com.kevin.legion.media.SpotifyController.SeekOutcome
import com.kevin.legion.media.SpotifyController.TransportWriteResult
import com.spotify.protocol.types.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM coverage for [SpotifyController]'s shuffle/repeat/seek pure functions (ticket 06,
 * `.scratch/spotify-voice/issues/06-shuffle-repeat-seek.md`) - same shape as the other
 * SpotifyController*Test files. The claim under test that matters most, per ticket 06 rule 4:
 * **the spoken line always reads the RESULT, never the request.**
 */
class SpotifyControllerTransportStateTest {

    // ------------------------------------------------------- shuffleMessage

    @Test
    fun `shuffleMessage reads the resulting isShuffling, not a request`() {
        assertEquals(
            "Shuffle's on.",
            SpotifyController.shuffleMessage(TransportWriteResult(isShuffling = true, repeatMode = Repeat.OFF)),
        )
        assertEquals(
            "Shuffle's off.",
            SpotifyController.shuffleMessage(TransportWriteResult(isShuffling = false, repeatMode = Repeat.OFF)),
        )
    }

    // ------------------------------------------------------- repeatMessage

    @Test
    fun `repeatMessage distinguishes track repeat from context repeat - they are different requests`() {
        val track = SpotifyController.repeatMessage(TransportWriteResult(isShuffling = false, repeatMode = Repeat.ONE))
        val context = SpotifyController.repeatMessage(TransportWriteResult(isShuffling = false, repeatMode = Repeat.ALL))
        val off = SpotifyController.repeatMessage(TransportWriteResult(isShuffling = false, repeatMode = Repeat.OFF))
        assertNotEquals("repeat-track and repeat-context must not read as the same sentence", track, context)
        assertTrue(track.contains("this track"))
        assertTrue(context.contains("whole"))
        assertTrue(off.contains("off"))
    }

    // ------------------------------------------------------- SeekOutcome shape

    @Test
    fun `TrackChanged and Landed are distinct outcomes - seeking past the end is never reported as a plain jump`() {
        assertNotEquals(SeekOutcome.TrackChanged, SeekOutcome.Landed(1000L))
    }

    @Test
    fun `Landed carries the resulting position, which may be unknown if the confirm-read failed`() {
        assertEquals(5000L, (SeekOutcome.Landed(5000L) as SeekOutcome.Landed).positionMs)
        assertEquals(null, (SeekOutcome.Landed(null) as SeekOutcome.Landed).positionMs)
    }
}
