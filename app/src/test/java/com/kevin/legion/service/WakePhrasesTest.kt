@file:Suppress("MaxLineLength") // test fixtures and phrase lists read best on one line

package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first tests over the wake-word phrase list, which had none at all before this
 * (`WakeWordEngine` is an `object` welded to `AudioRecord`/`Model`/`Context` with no seam).
 * [WakePhrases] exists partly so that this file can: the phrase decisions are pure string work
 * and there was no reason for them to be reachable only through a microphone.
 */
class WakePhrasesTest {

    // --- grammar ----------------------------------------------------------

    @Test
    fun `grammar is exactly hey plus the lowercased name`() {
        assertEquals(listOf("hey alfred"), WakePhrases.grammar("Alfred"))
        assertEquals(listOf("hey kratos"), WakePhrases.grammar("  KRATOS  "))
    }

    /**
     * Ticket 09 restored (2026-10-03): the only wake phrase needs a name, so a blank name is an
     * EMPTY grammar, which `WakeWordEngine.start` and `WakeKeywords.build` refuse in words.
     */
    @Test
    fun `a blank name yields an empty grammar`() {
        assertEquals(emptyList<String>(), WakePhrases.grammar(""))
        assertEquals(emptyList<String>(), WakePhrases.grammar("   "))
    }

    @Test
    fun `excelsior is no longer a wake phrase`() {
        assertFalse(WakePhrases.grammar("Alfred").contains("excelsior"))
    }

    // --- sleep phrase -----------------------------------------------------

    @Test
    fun `the plain sleep phrase matches`() {
        assertTrue(WakePhrases.isSleepPhrase("that will be all"))
        assertTrue(WakePhrases.isSleepPhrase("That will be all."))
        assertTrue(WakePhrases.isSleepPhrase("  That will be all!  "))
    }

    @Test
    fun `the contraction matches`() {
        assertTrue(WakePhrases.isSleepPhrase("that'll be all"))
        assertTrue(WakePhrases.isSleepPhrase("Thank you, that'll be all."))
    }

    @Test
    fun `a lead-in still matches because the phrase ends the utterance`() {
        assertTrue(WakePhrases.isSleepPhrase("ok great, that will be all"))
    }

    /**
     * The false-positive this anchors against. "That will be all I need from the pantry" contains
     * the phrase and is plainly not a dismissal; a `contains` check would hang up on it.
     */
    @Test
    fun `the phrase inside a longer sentence does not match`() {
        assertFalse(WakePhrases.isSleepPhrase("that will be all I need from the pantry"))
        assertFalse(WakePhrases.isSleepPhrase("that will be all the milk we have"))
    }

    /**
     * REVERSED 2026-11-23 (Kevin: "that'll be all" and "bye bye" sometimes did not close the
     * conversation). These two were documented, deliberate misses, left to the model's own
     * `end_conversation` tool on the reasoning that a missed backstop costs one turn while a false
     * one hangs up mid-sentence. The model turned out to be the unreliable half, so the backstop
     * takes them now. The old test said a change here is a decision, not a bug fix: this is it,
     * written down where that one was.
     */
    @Test
    fun `a trailing politeness matches, reversing the 2026-11-20 deliberate miss`() {
        assertTrue(WakePhrases.isSleepPhrase("that will be all, thanks"))
        assertTrue(WakePhrases.isSleepPhrase("that will be all for now"))
    }

    /**
     * Kevin, 2026-11-23: a goodbye with filler around it must close the conversation. Courtesy at
     * either END is set aside ([Farewells.withoutCourtesy]); a request that merely contains a
     * farewell, or a request wrapped in the same courtesy, is still a request. Both tables are data:
     * a new courtesy word is a row here, never a new branch. Invented utterances only.
     */
    @Test
    fun `courtesy around a stop is set aside, courtesy around a request changes nothing`() {
        for (s in COURTESY_CLOSES) assertTrue(s, WakePhrases.isSleepPhrase(s, "Alfred"))
        for (s in COURTESY_REQUESTS) assertFalse(s, WakePhrases.isSleepPhrase(s, "Alfred"))
    }

    companion object {
        /** Closes: a farewell or a dismissal with lead-ins, thanks, the name or a tail around it. */
        val COURTESY_CLOSES = listOf(
            "okay, that'll be all, thanks Alfred", "that's all for now", "that will be all, thanks",
            "that will be all for now", "alright, bye bye", "okay that's all, thanks", "bye bye, please",
            "Thanks Alfred, that'll be all then", "that's it for now", "alright, that'll be all",
            "goodbye for now, please", "Okay. Bye bye, Alfred. Thanks.", "that's all, thanks Alfred",
        )

        /** Never closes: a real request, including one wearing the same courtesy. */
        val COURTESY_REQUESTS = listOf(
            "text mom bye bye", "say good night to the kids", "thanks, that's all the milk we need",
            "okay, add eggs for now", "please stop the music", "thanks, remind me to say goodbye then",
            "alright, what's the plan for now", "okay, play bye bye birdie please", "that's all I need, thanks",
            "stop the timer please", "goodbye for now is the title of the song", "thanks", "please", "for now",
        )
    }

    @Test
    fun `unrelated speech never matches`() {
        assertFalse(WakePhrases.isSleepPhrase(""))
        assertFalse(WakePhrases.isSleepPhrase("   "))
        assertFalse(WakePhrases.isSleepPhrase("is that all"))
        assertFalse(WakePhrases.isSleepPhrase("what will it all cost"))
        assertFalse(WakePhrases.isSleepPhrase("add milk to the list"))
    }

    // --- stop and goodbye (2026-11-18) ---

    @Test
    fun `every stop and goodbye phrase ends the conversation when it is the whole turn`() {
        for (p in listOf(
            "stop", "never mind", "that's all", "that's it", "that will be all", "that'll be all", "go away",
            "stand down", "bye", "goodbye", "good bye", "thanks bye", "ok bye", "we're done", "I'm done",
        )) {
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred"))
            assertTrue("$p.", WakePhrases.isSleepPhrase("${p.replaceFirstChar { it.uppercase() }}.", "Alfred"))
        }
    }

    @Test
    fun `the companion's name and a leading hey or ok are not part of the phrase`() {
        for (s in listOf("Goodbye, Alfred.", "Alfred, stop.", "ok bye", "Okay, goodbye.", "Hey Alfred, that's all.",
            "Bye Alfred!", "OK, never mind.")) {
            assertTrue(s, WakePhrases.isSleepPhrase(s, "Alfred"))
        }
        // Another companion name is just a name: the rule follows the name he gave it.
        assertTrue(WakePhrases.isSleepPhrase("Goodbye, Jarvis.", "Jarvis"))
        assertFalse(WakePhrases.isSleepPhrase("Goodbye, Jarvis.", "Alfred"))
    }

    /** 2026-11-22: a thanks before the name and the stop kept the stop from matching. */
    @Test
    fun `thanks and the name before a stop are set aside`() {
        for (s in listOf(
            "Thanks, Alfred, that's all.", "Thank you Alfred, that's it.", "Thanks Alfred. That's all.",
            "Cheers, Alfred, we're done.", "Okay, thanks, never mind.", "Thank you, that's all.",
        )) {
            assertTrue(s, WakePhrases.isSleepPhrase(s, "Alfred"))
        }
        for (s in listOf("Thanks, Alfred.", "Thank you.", "Thanks, Alfred, add eggs.", "Thanks, that's all the milk")) {
            assertFalse(s, WakePhrases.isSleepPhrase(s, "Alfred"))
        }
    }

    /** After a question the thanks is set aside too, and only a hard stop still ends it. */
    @Test
    fun `after a question a thanks before a hard stop ends it, before a soft one it does not`() {
        for (s in listOf("Thanks, goodbye.", "Thank you, Alfred, stop.", "Cheers, bye.")) {
            assertTrue(s, WakePhrases.isSleepPhrase(s, "Alfred", afterQuestion = true))
        }
        for (s in listOf("Thanks, that's all.", "Thank you, Alfred, that's it.", "Thanks, never mind.")) {
            assertFalse(s, WakePhrases.isSleepPhrase(s, "Alfred", afterQuestion = true))
        }
    }

    @Test
    fun `sentences that only use those words never end it`() {
        for (s in listOf(
            "stop the music", "that's all I need from the pantry", "thanks", "thank you", "nothing",
            "stop the timer", "never mind the calendar, what's the weather", "say goodbye to my sister for me",
            "is that it?", "that's it for the groceries, add eggs", "bye bye birdie is on tonight",
        )) {
            assertFalse(s, WakePhrases.isSleepPhrase(s, "Alfred"))
        }
    }

    @Test
    fun `the review's missing stops end it too`() {
        for (p in listOf("Nevermind.", "Bye bye!", "Stop, stop.", "Thank you, bye.", "Thanks, goodbye.", "That's all, thanks.", "That's it, thanks.")) {
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred"))
        }
    }

    @Test
    fun `after a question only a hard stop ends it, the soft ones may be his answer`() {
        // Kevin, 2026-11-20: a goodbye ends the conversation reliably, after a question too ([Farewells]).
        for (p in listOf("Stop.", "Goodbye.", "Good bye, Alfred.", "Bye.", "Bye bye.", "Go away.", "Stand down.", "Thanks, bye.")) {
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred", afterQuestion = true))
        }
        for (p in listOf("That's it.", "That's all.", "Never mind.", "We're done.", "I'm done.", "That will be all.")) {
            assertFalse(p, WakePhrases.isSleepPhrase(p, "Alfred", afterQuestion = true))
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred"))
        }
    }
}
