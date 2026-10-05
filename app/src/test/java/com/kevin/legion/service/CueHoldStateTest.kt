package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live session's side of "a cue wins" (mapbox-nav ticket 05): what a hold takes, what a release
 * gives back and when. The session itself needs a socket and an AudioTrack, so the sequence lives in
 * [CueHoldState] where it can be walked here.
 */
class CueHoldStateTest {
    private val state = CueHoldState()

    @Test fun aHoldPausesPlaybackAndGatesTheMicWhetherTheAssistantOrTheUserIsTalking() {
        // The state does not ask who is talking: pausing an idle track is a no-op in the session and
        // gating the mic during the user's sentence is exactly what keeps the cue out of the transcript.
        state.hold()
        assertTrue(state.playbackPaused)
        assertTrue(state.micHeld)
    }

    @Test fun aReleaseResumesPlaybackButKeepsTheMicGatedUntilTheResumedReplyHasDrained() {
        state.hold()
        val token = state.release()
        assertFalse("the reply resumes", state.playbackPaused)
        assertTrue("its tail must not be heard as the user", state.micHeld)
        state.drained(token)
        assertFalse(state.micHeld)
    }

    @Test fun aSecondCueTakenBeforeTheDrainIsNotUndoneByTheFirstReleasesLateDrain() {
        state.hold()
        val first = state.release()
        state.hold()
        state.drained(first)
        assertTrue("the newer hold stands", state.micHeld)
        assertTrue(state.playbackPaused)
        state.drained(state.release())
        assertFalse(state.micHeld)
    }

    @Test fun aSessionThatClosesMidCueLeavesNothingHeld() {
        state.hold()
        state.reset()
        assertFalse(state.playbackPaused)
        assertFalse(state.micHeld)
    }

    @Test fun theBridgeOnlyForgetsTheSessionThatIsCurrent() {
        val a = object : AssistantCueHold {
            override fun holdForCue() = Unit
            override fun releaseAfterCue() = Unit
        }
        val b = object : AssistantCueHold {
            override fun holdForCue() = Unit
            override fun releaseAfterCue() = Unit
        }
        AssistantCueBridge.register(a)
        AssistantCueBridge.register(b)
        AssistantCueBridge.unregister(a)
        assertTrue("a newer session replaced the older; the older closing changes nothing",
            AssistantCueBridge.current === b)
        AssistantCueBridge.unregister(b)
        assertTrue(AssistantCueBridge.current == null)
    }
}
