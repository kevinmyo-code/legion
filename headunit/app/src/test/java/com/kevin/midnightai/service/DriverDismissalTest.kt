package com.kevin.midnightai.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DriverDismissal] is the deterministic half of ending a conversation by voice, so the cases that
 * matter are the ones a substring scan would get wrong in a car.
 */
class DriverDismissalTest {

    private fun closes(turn: String, name: String = "Moose", trivia: Boolean = false) =
        DriverDismissal.isDismissal(turn, name, trivia)

    // --- the four the brief names ---------------------------------------------------------

    @Test fun `the four goodbyes a driver actually says all close`() {
        assertTrue(closes("that'll be all"))
        assertTrue(closes("okay bye"))
        assertTrue(closes("bye thanks"))
        assertTrue(closes("good night"))
    }

    @Test fun `a request that merely contains a goodbye does not close`() {
        // The whole reason this is a decomposition and not a contains() check.
        assertFalse(closes("text mom bye bye"))
        assertFalse(closes("say good night to the kids"))
        assertFalse(closes("that's all the coolant it needs"))
        assertFalse(closes("stop the music"))
        assertFalse(closes("that's it for the oil change"))
        assertFalse(closes("tell me about the suicide doors"))
    }

    // --- normalisation ------------------------------------------------------------------

    @Test fun `punctuation casing and both apostrophes normalise to the same turn`() {
        assertTrue(closes("Bye-bye."))
        assertTrue(closes("BYE"))
        assertTrue(closes("  that will be all  "))
        assertTrue(closes("that'll be all"))
        assertTrue(closes("that’ll be all")) // curly apostrophe, common in transcripts
        assertTrue(closes("That's it."))
        assertTrue(closes("that’s all"))
    }

    @Test fun `the dismissal data is written in the normalised form the matcher compares against`() {
        // An apostrophe becomes a SPACE here, unlike CrisisDetector which deletes it. Phone phrase
        // data read through the wrong normaliser matches nothing, silently.
        DriverDismissal.DISMISSALS.forEach {
            assertTrue(it, DriverDismissal.normalise(it) == it)
        }
        assertTrue(DriverDismissal.normalise("that'll be all") == "that ll be all")
    }

    // --- courtesy, at the ends only ------------------------------------------------------

    @Test fun `courtesy at either end comes off`() {
        assertTrue(closes("okay, that'll be all, thanks Moose"))
        assertTrue(closes("Moose, stop"))
        assertTrue(closes("goodbye Moose"))
        assertTrue(closes("that's all for now"))
        assertTrue(closes("alright, good night then"))
        assertTrue(closes("well, see you later"))
    }

    @Test fun `a renamed companion still gets his name stripped`() {
        assertTrue(closes("bye Uncle Moose", name = "Uncle Moose"))
        assertTrue(closes("bye Zero", name = "Zero"))
        // And a name that is not his is not courtesy: this is a request about someone else.
        assertFalse(closes("bye Dave", name = "Moose"))
    }

    @Test fun `the default two-word name is stripped by its last word too`() {
        // CompanionProfile.DEFAULT_NAME is "Uncle Moose" and nobody says the whole thing.
        val default = "Uncle Moose"
        assertTrue(closes("bye Moose", name = default))
        assertTrue(closes("Moose, stop", name = default))
        assertTrue(closes("good night Moose", name = default))
        assertTrue(closes("that'll be all Moose", name = default))
        // The title alone is not him.
        assertFalse(closes("bye Uncle", name = default))
        // And the name alone is still not a goodbye.
        assertFalse(closes("Moose", name = default))
    }

    @Test fun `courtesy alone is a turn of its own and closes nothing`() {
        assertFalse(closes("thanks"))
        assertFalse(closes("cheers"))
        assertFalse(closes("okay"))
        assertFalse(closes("please"))
        assertFalse(closes(""))
        assertFalse(closes("   "))
    }

    @Test fun `two ways of saying one goodbye is still one dismissal`() {
        assertTrue(closes("that's it, bye bye"))
        assertTrue(closes("goodbye good night"))
        assertTrue(closes("bye bye bye"))
        // but not a phrase that merely starts with one
        assertFalse(closes("bye bye birdie"))
    }

    // --- the answer-word veto: an authorisation is never read as a goodbye (the phone's F1) ---
    // NOT a write guard. The dismissal is read at the end of the MODEL's turn, by which point
    // handleToolCall already dispatched anything it asked for. Closing stops the NEXT turn, not
    // this one. See DriverDismissal's class doc.

    @Test fun `a yes that authorises work is never swallowed by a goodbye`() {
        // Ruling A: the bare dismissal closes even while Moose is waiting on a yes.
        assertTrue(closes("that'll be all"))
        assertTrue(closes("that's all"))
        // But a turn holding an answer word goes to the model, so the garage pulse or the service
        // mileage it authorises still runs.
        assertFalse(closes("yes, that'll be all"))
        assertFalse(closes("yeah bye"))
        assertFalse(closes("sure, goodbye"))
        assertFalse(closes("go ahead, that's it"))
    }

    @Test fun `a number in the turn is an answer, not a goodbye`() {
        // A bare "Five." once switched the active car on the phone. Any digit vetoes here.
        assertFalse(closes("120000 that's it"))
        assertFalse(closes("bye 2003"))
    }

    @Test fun `a filler lead-in authorises nothing and vetoes nothing`() {
        // Kevin, Q9: a filler word alone must not read as a yes.
        assertTrue(closes("okay bye"))
        assertTrue(closes("alright, goodbye"))
        assertTrue(closes("right, that'll be all"))
        assertTrue(closes("ok then, bye"))
    }

    // --- the gap this port leaves open, asserted so it is a decision and not a surprise ---

    @Test fun `the ambiguous lone farewells deliberately do not close`() {
        // "Morning or night?" "Night." is an answer. Only the strict branch is ported, so these
        // three are left to the model (DriverDismissal's class doc).
        assertFalse(closes("night"))
        assertFalse(closes("nite"))
        assertFalse(closes("later"))
        // The soft dismissals the phone also left soft.
        assertFalse(closes("never mind"))
        assertFalse(closes("we're done"))
        // The two-word forms are phrases in their own right and do close.
        assertTrue(closes("good night"))
        assertTrue(closes("night night"))
        assertTrue(closes("see you later"))
    }

    @Test fun `an assenting lead before a goodbye costs one turn, by design`() {
        // The veto runs in every state here because the unit has no "answer awaited" signal, so
        // these go to the model rather than closing. Asserted so the trade is visible: one extra
        // turn is the price of never losing an authorisation.
        assertFalse(closes("great, that'll be all"))
        assertFalse(closes("cool, bye"))
        assertFalse(closes("perfect, good night"))
        // "see you tomorrow" can never match: "tomorrow" is an answer word, which is why the
        // phrase is deliberately absent from FAREWELLS rather than sitting there as dead data.
        assertFalse(closes("see you tomorrow"))
        assertFalse("dead phrase data", DriverDismissal.FAREWELLS.contains("see you tomorrow"))
    }

    // --- trivia ---------------------------------------------------------------------------

    @Test fun `a running trivia game is not ended by a shouted right answer`() {
        assertFalse(closes("that's it", trivia = true))
        assertFalse(closes("that's all", trivia = true))
        assertFalse(closes("that'll be all", trivia = true))
        // A goodbye or a stop still ends it, and so does the tap.
        assertTrue(closes("bye", trivia = true))
        assertTrue(closes("good night", trivia = true))
        assertTrue(closes("stop", trivia = true))
        // Outside a game the same words close.
        assertTrue(closes("that's it", trivia = false))
    }

    // --- which sessions arm at all --------------------------------------------------------

    @Test fun `only the tap-driven conversation arms`() {
        fun arms(vad: Boolean, warm: Boolean, active: Boolean) = DriverDismissal.armsDismissal(
            heard = "that'll be all", companionName = "Moose",
            vadMode = vad, keepWarm = warm, conversationActive = active, inTriviaGame = false,
        )
        assertTrue(arms(vad = true, warm = true, active = true))
        // Conversational onboarding: same session class, keepWarm false. A cheerful "bye" mid
        // questionnaire must not park the socket and read to OnboardingManager as a failed run.
        assertFalse(arms(vad = true, warm = false, active = true))
        // A speak-only proactive line: no mic, nobody being listened to.
        assertFalse(arms(vad = false, warm = true, active = true))
        // Warm but between conversations.
        assertFalse(arms(vad = true, warm = true, active = false))
    }

    @Test fun `a non-goodbye turn never arms, whatever the session`() {
        assertFalse(
            DriverDismissal.armsDismissal(
                heard = "what's my coolant temp", companionName = "Moose",
                vadMode = true, keepWarm = true, conversationActive = true, inTriviaGame = false,
            )
        )
    }
}
