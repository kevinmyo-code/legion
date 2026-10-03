package com.kevin.legion.navigation.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every call, in order, so a test reads the arbitration as a script. */
private class FakeCueEnv : CueEnvironment {
    override var muted = false
    override var canSpeak = true
    var engineAccepts = true
    val log = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    val unspokenReasons = mutableListOf<String>()
    val droppedReasons = mutableListOf<String>()

    override fun dropped(why: String) { droppedReasons += why }

    override fun hold() { log += "hold" }

    override fun release() { log += "release" }

    override fun speak(text: String): Boolean {
        log += "speak:$text"
        if (engineAccepts) spoken += text
        return engineAccepts
    }

    override fun stopSpeaking() { log += "stop" }

    override fun unspoken(text: String, why: String) {
        log += "unspoken:$why"
        unspokenReasons += why
    }
}

class NavCueArbiterTest {
    private val env = FakeCueEnv()
    private val arbiter = NavCueArbiter(env)

    @Test fun aCueHoldsTheAssistantAndMicSpeaksThenGivesBothBack() {
        arbiter.onCue("Turn left")
        assertEquals(listOf("hold", "speak:Turn left"), env.log)
        assertTrue(arbiter.busy)
        arbiter.onSpeechDone()
        assertEquals(listOf("hold", "speak:Turn left", "release"), env.log)
        assertFalse(arbiter.busy)
    }

    @Test fun theHoldIsTakenBeforeTheFirstWordSoAReplyIsPausedAndTheMicGatedFirst() {
        // Cue during assistant speech and during user speech are the same call: the environment's
        // hold() pauses a reply that is playing and gates the mic whichever of the two is going on.
        arbiter.onCue("In 300 feet, turn right")
        assertEquals("hold", env.log.first())
        assertEquals("speak:In 300 feet, turn right", env.log[1])
    }

    @Test fun aMutedCueIsDroppedWithoutTouchingTheAssistantOrTheMic() {
        env.muted = true
        arbiter.onCue("Turn left")
        assertTrue(env.log.isEmpty())
        assertFalse(arbiter.busy)
        // Reported as a reason only, never the cue text (phone run 4, defect 3).
        assertEquals(listOf("muted"), env.droppedReasons)
    }

    @Test fun aBlankCueIsNotReportedAsDropped() {
        env.muted = true
        arbiter.onCue(" ")
        assertTrue(env.droppedReasons.isEmpty())
    }

    @Test fun muteWhileACueIsSpeakingStopsItAndGivesTheAssistantBack() {
        arbiter.onCue("Turn left")
        env.muted = true
        arbiter.cancel()
        assertEquals(listOf("hold", "speak:Turn left", "stop", "release"), env.log)
        // The engine's late "done" for the stopped cue must not release a second time.
        arbiter.onSpeechDone()
        assertEquals(1, env.log.count { it == "release" })
    }

    @Test fun twoCuesBackToBackAreSaidInOrderUnderOneHold() {
        arbiter.onCue("In a quarter mile, turn left")
        arbiter.onCue("Turn left")
        assertEquals("the second waits for the first", listOf("hold", "speak:In a quarter mile, turn left"), env.log)
        arbiter.onSpeechDone()
        assertEquals(
            listOf("hold", "speak:In a quarter mile, turn left", "speak:Turn left"),
            env.log,
        )
        assertEquals("the assistant is not resumed between them", 0, env.log.count { it == "release" })
        arbiter.onSpeechDone()
        assertEquals("release", env.log.last())
        assertEquals(1, env.log.count { it == "hold" })
        assertEquals(1, env.log.count { it == "release" })
    }

    @Test fun onlyTheNewestWaitingCueSurvivesBecauseAnOlderOneIsStale() {
        arbiter.onCue("one")
        arbiter.onCue("two")
        arbiter.onCue("three")
        arbiter.onSpeechDone()
        arbiter.onSpeechDone()
        assertEquals(listOf("one", "three"), env.spoken)
    }

    @Test fun aCueThatWaitedThroughAMuteIsNotSaid() {
        arbiter.onCue("one")
        arbiter.onCue("two")
        env.muted = true
        arbiter.onSpeechDone()
        assertEquals(listOf("one"), env.spoken)
        assertEquals("release", env.log.last())
    }

    @Test fun noSpeechEngineMeansTheCueIsSaidNowhereAndIsReported() {
        env.canSpeak = false
        arbiter.onCue("Turn left")
        assertEquals(listOf("unspoken:no speech engine"), env.log)
        assertTrue("nothing was held for a cue that will not be said", env.spoken.isEmpty())
        assertFalse(arbiter.busy)
    }

    @Test fun anEngineThatRefusesTheCueReleasesAtOnceAndReportsIt() {
        env.engineAccepts = false
        arbiter.onCue("Turn left")
        assertEquals(listOf("hold", "speak:Turn left", "unspoken:the speech engine refused it", "release"), env.log)
        assertFalse(arbiter.busy)
        env.engineAccepts = true
        arbiter.onCue("Turn right")
        assertTrue("a later cue still works", env.spoken.contains("Turn right"))
    }

    @Test fun aBlankCueIsIgnored() {
        arbiter.onCue("   ")
        assertTrue(env.log.isEmpty())
    }

    @Test fun aDoneWithNothingSpeakingIsIgnored() {
        arbiter.onSpeechDone()
        assertTrue(env.log.isEmpty())
    }

    @Test fun cancelWithNothingHeldDoesNothing() {
        arbiter.cancel()
        assertTrue(env.log.isEmpty())
    }
}
