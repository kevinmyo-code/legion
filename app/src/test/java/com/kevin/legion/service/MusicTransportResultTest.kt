package com.kevin.legion.service

import com.kevin.legion.media.TransportReadback.Move
import com.kevin.legion.media.TransportReadback.Observation
import com.kevin.legion.media.TransportReadback.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `control_music`'s transport result may let Alfred say
 * (`music-suggestions-1124` P4). Pure: the three wordings and the two flags, nothing else.
 */
class MusicTransportResultTest {

    @Test
    fun `success is confirmed only`() {
        val ok = MusicControlToolbox.readbackResult(Move.PLAY, Verdict.CONFIRMED, Observation(playing = true))
        assertTrue(ok.getBoolean("success"))
        assertEquals("playing", ok.getString("playback"))
        assertFalse("a confirmed result needs no caveat", ok.has("message"))
    }

    @Test
    fun `an unverified dispatch says so instead of confirming`() {
        val unknown = MusicControlToolbox.readbackResult(Move.PLAY, Verdict.CANNOT_TELL, null)
        assertFalse("success is what an outcome verb stands on", unknown.getBoolean("success"))
        assertTrue(unknown.getString("message").contains("can't say whether it took"))
        // The command DID go out, and the hands path needs that apart from the outcome.
        assertTrue(unknown.getBoolean("dispatched"))
        assertFalse("nothing was read, so no state may be reported", unknown.has("playback"))
    }

    @Test
    fun `a contradicted command reports the state the player actually holds`() {
        val no = MusicControlToolbox.readbackResult(Move.PAUSE, Verdict.CONTRADICTED, Observation(playing = true))
        assertFalse(no.getBoolean("success"))
        assertTrue(no.getString("message").contains("still reports playing"))
        assertEquals("playing", no.getString("playback"))
        assertTrue(no.getBoolean("dispatched"))
    }

    @Test
    fun `each move is named in its own words`() {
        fun words(m: Move) = MusicControlToolbox.readbackResult(m, Verdict.CANNOT_TELL, null).getString("message")
        assertTrue(words(Move.PLAY).contains("the play"))
        assertTrue(words(Move.PAUSE).contains("the pause"))
        assertTrue(words(Move.NEXT).contains("the skip"))
        assertTrue(words(Move.PREVIOUS).contains("the go back"))
    }
}
