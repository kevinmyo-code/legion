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
     * Documented, deliberate misses. A trailing politeness does NOT match the deterministic
     * backstop - the model's own `end_conversation` tool is told to treat these as dismissals, and
     * erring toward one extra turn beats erring toward hanging up mid-sentence. If this behaviour
     * is ever changed, it is a decision, not a bug fix, and this test should change with it.
     */
    @Test
    fun `a trailing politeness is left to the model`() {
        assertFalse(WakePhrases.isSleepPhrase("that will be all, thanks"))
        assertFalse(WakePhrases.isSleepPhrase("that will be all for now"))
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
        for (p in listOf("Stop.", "Goodbye.", "Good bye, Alfred.", "Bye.", "Bye bye.", "Go away.", "Stand down.")) {
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred", afterQuestion = true))
        }
        for (p in listOf("That's it.", "That's all.", "Never mind.", "We're done.", "I'm done.", "That will be all.", "Thanks, bye.")) {
            assertFalse(p, WakePhrases.isSleepPhrase(p, "Alfred", afterQuestion = true))
            assertTrue(p, WakePhrases.isSleepPhrase(p, "Alfred"))
        }
    }
}
