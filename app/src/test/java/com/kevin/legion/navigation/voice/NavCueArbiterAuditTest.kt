package com.kevin.legion.navigation.voice

import com.kevin.legion.navigation.audit.CueOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every cue the arbiter is handed ends as exactly one NAV_AUDIT outcome; the text never leaves it. */
class NavCueArbiterAuditTest {
    private val outcomes = mutableListOf<CueOutcome>()

    private val env = object : CueEnvironment {
        override var muted = false
        override var canSpeak = true
        var accepts = true

        override fun hold() = Unit

        override fun release() = Unit

        override fun speak(text: String) = accepts

        override fun stopSpeaking() = Unit

        override fun unspoken(text: String, why: String) = Unit
    }

    private val arbiter = NavCueArbiter(env) { outcomes += it }

    @Test fun aSpokenCueCountsOnce() {
        arbiter.onCue("Turn left")
        arbiter.onSpeechDone()
        assertEquals(listOf(CueOutcome.SPOKEN), outcomes)
    }

    @Test fun aWaitingCueReplacedByANewerOneIsSuperseded() {
        arbiter.onCue("In 300 feet, turn left")
        arbiter.onCue("In 100 feet, turn left")
        arbiter.onCue("Turn left")
        arbiter.onSpeechDone()
        arbiter.onSpeechDone()
        assertEquals(listOf(CueOutcome.SPOKEN, CueOutcome.SUPERSEDED, CueOutcome.SPOKEN), outcomes)
    }

    @Test fun mutedNoEngineAndRefusedAreEachTheirOwnDrop() {
        env.muted = true
        arbiter.onCue("a")
        env.muted = false
        env.canSpeak = false
        arbiter.onCue("b")
        env.canSpeak = true
        env.accepts = false
        arbiter.onCue("c")
        arbiter.onCue("   ")
        assertEquals(listOf(CueOutcome.MUTED, CueOutcome.NO_ENGINE, CueOutcome.REFUSED), outcomes)
    }

    @Test fun aWaitingCueDroppedByATripEndIsCancelledAndTheOneSpeakingStaysSpoken() {
        arbiter.onCue("a")
        arbiter.onCue("b")
        arbiter.cancel()
        assertEquals(listOf(CueOutcome.SPOKEN, CueOutcome.CANCELLED), outcomes)
    }

    @Test fun aWaitingCueMutedBeforeItsTurnCountsAsMuted() {
        arbiter.onCue("a")
        arbiter.onCue("b")
        env.muted = true
        arbiter.onSpeechDone()
        assertEquals(listOf(CueOutcome.SPOKEN, CueOutcome.MUTED), outcomes)
    }
}
