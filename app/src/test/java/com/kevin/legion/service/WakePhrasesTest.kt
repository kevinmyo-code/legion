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
    fun `grammar always contains the fixed wake phrase`() {
        assertTrue(WakePhrases.grammar("Alfred").contains("excelsior"))
    }

    /**
     * The regression that matters most: a blank companion name used to build an EMPTY grammar,
     * which `WakeWordEngine.start` then had to refuse outright rather than listen for nothing.
     * The fixed phrase does not depend on a name, so there is always something to listen for now.
     */
    @Test
    fun `a blank name still yields a usable grammar`() {
        assertEquals(listOf("excelsior"), WakePhrases.grammar(""))
        assertEquals(listOf("excelsior"), WakePhrases.grammar("   "))
    }

    /**
     * "hey <name>" is retained behind the fixed phrase until the phone confirms "excelsior" is in
     * the small Vosk model's lexicon. When Kevin confirms it fires, this test is the one that
     * should be deleted alongside the second grammar entry - it is asserting a temporary state on
     * purpose, and it should fail loudly if someone removes the fallback without saying so.
     */
    @Test
    fun `the companion name phrase is retained and lowercased`() {
        assertEquals(listOf("excelsior", "hey dorothy"), WakePhrases.grammar("Dorothy"))
        assertEquals(listOf("excelsior", "hey kratos"), WakePhrases.grammar("  KRATOS  "))
    }

    @Test
    fun `a companion literally named excelsior does not duplicate the entry`() {
        assertEquals(listOf("excelsior", "hey excelsior"), WakePhrases.grammar("Excelsior"))
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
}
