package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TrailingFiller]: a wait line after real content is dropped; content is never held long. Invented lines. */
class TrailingFillerTest {
    private fun run(vararg deltas: String, before: Boolean = false): Pair<String, Int> {
        val f = TrailingFiller { before }
        val out = StringBuilder()
        deltas.forEach { out.append(f.feed(it)) }
        out.append(f.flush())
        return out.toString() to f.dropped
    }

    @Test
    fun `the wait lines seen on the phone are filler, answers are not`() {
        for (s in listOf("Just a second.", "Give me a second with that.", "One sec.", "Hang on.", "Bear with me.",
            "Checking.", "One moment.", "Let me look.", "Okay, just a moment.")) {
            assertTrue(s, TrailingFiller.isFiller(s))
        }
        for (s in listOf("It is mild.", "Just a second ago it rang.", "Give me the codes.", "One code is stored.",
            "I missed that.", "Hang on to the receipt.")) {
            assertFalse(s, TrailingFiller.isFiller(s))
        }
    }

    @Test
    fun `after content a wait line is dropped, streamed in pieces or not`() {
        assertEquals("I missed that. " to 1, run("I missed that. Give me a second with that."))
        assertEquals("I missed that. " to 1, run("I missed th", "at. Give me a sec", "ond with that."))
        assertEquals("" to 1, run("Just a second.", before = true))
    }

    @Test
    fun `a leading wait line or a lone one is kept`() {
        assertEquals("Just a second. It is mild." to 0, run("Just a second. It is mild."))
        assertEquals("On it." to 0, run("On it."))
    }

    @Test
    fun `text that cannot be filler passes at once, not at the sentence end`() {
        val f = TrailingFiller { false }
        assertEquals("The quiz is due ", f.feed("The quiz is due "))
        assertEquals("at 3", f.feed("at 3."))
        assertEquals(". Then", f.feed(" Then"))
        assertEquals("", f.flush())
    }
}
