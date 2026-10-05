package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Typed chat (web-assistant 09) and navigation turn cues (mapbox-nav 05/11) both act on the Live
 * session's playback and mic. These walk the shared decisions in [ReplyAudioGate] against
 * [CueHoldState]. The session itself needs a socket and an AudioTrack, so what is tested is the
 * decision seam the session calls at both sites, not the session.
 */
class CueDuringTypedReplyTest {
    private val cue = CueHoldState()

    @Test fun aTypedRepliesAudioIsDroppedWhateverTheCueIsDoing() {
        assertTrue(ReplyAudioGate.drops(superseded = false, typedTurnInFlight = true))
        cue.hold()
        assertTrue("during a cue", ReplyAudioGate.drops(superseded = false, typedTurnInFlight = true))
        val token = cue.release()
        assertTrue("right after the cue is released", ReplyAudioGate.drops(false, true))
        cue.drained(token)
        assertTrue("after the mic gate reopens", ReplyAudioGate.drops(false, true))
    }

    @Test fun aCueStillGatesTheMicAndPausesPlaybackDuringATypedConversation() {
        cue.hold()
        assertTrue("the mic is gated so the cue is not heard as the user", cue.micHeld)
        assertFalse("nothing may start the track under a cue", ReplyAudioGate.mayStartPlayback(cue))
    }

    @Test fun aSpokenRepliesChunkIsKeptDuringACueButNotStartedUntilItsRelease() {
        cue.hold()
        assertFalse(ReplyAudioGate.drops(superseded = false, typedTurnInFlight = false))
        assertFalse(ReplyAudioGate.mayStartPlayback(cue))
        cue.release()
        assertTrue(ReplyAudioGate.mayStartPlayback(cue))
    }

    @Test fun aSupersededChunkIsDroppedTypedOrNot() {
        assertTrue(ReplyAudioGate.drops(superseded = true, typedTurnInFlight = false))
    }
}
