package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [SentenceSplitter], [WordChunker] and [SpokenText] on invented replies. */
class SentenceSplitterTest {

    private fun splitAll(text: String, step: Int = 3): List<String> {
        val s = SentenceSplitter()
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            out += s.feed(text.substring(i, minOf(text.length, i + step)))
            i += step
        }
        s.flush()?.let { out += it }
        return out
    }

    @Test
    fun `sentences come out whole however the stream is chunked`() {
        val text = "Done. The timer is set for twelve minutes! Anything else? Fine."
        for (step in listOf(1, 2, 5, 64)) {
            assertEquals(
                listOf("Done.", "The timer is set for twelve minutes!", "Anything else?", "Fine."),
                splitAll(text, step),
            )
        }
    }

    @Test
    fun `a sentence is not emitted until what follows the stop is known`() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("It costs 3."))
        assertEquals(emptyList<String>(), s.feed("5 a gallon"))
        assertEquals(listOf("It costs 3.5 a gallon."), s.feed(". Next") )
    }

    @Test
    fun `decimals, money, times and abbreviations do not split`() {
        assertEquals(
            listOf(
                "Dr. Example said it was \$1,284.17 at 4:45 p.m. today.",
                "That is about 3.5 percent, e.g. a small rise.",
            ),
            splitAll("Dr. Example said it was \$1,284.17 at 4:45 p.m. today. That is about 3.5 percent, e.g. a small rise."),
        )
    }

    @Test
    fun `an initial is not a sentence end`() {
        assertEquals(listOf("Ask J. Doe about it."), splitAll("Ask J. Doe about it."))
    }

    @Test
    fun `no is a word, not an abbreviation`() {
        assertEquals(listOf("No.", "I can't do that."), splitAll("No. I can't do that."))
    }

    @Test
    fun `a long first sentence is cut at a clause so the first audio is not held back`() {
        val out = splitAll(
            "Your next oil change is due in about eleven hundred miles on the blue car, so there is " +
                "no rush this week. Second sentence, with a comma, stays whole.",
        )
        assertEquals("Your next oil change is due in about eleven hundred miles on the blue car,", out[0])
        assertEquals("so there is no rush this week.", out[1])
        assertEquals("Second sentence, with a comma, stays whole.", out[2])
    }

    @Test
    fun `a line break ends a sentence and flush returns the tail`() {
        val s = SentenceSplitter()
        assertEquals(listOf("First line"), s.feed("First line\nsecond"))
        assertEquals("second", s.flush())
        assertNull(s.flush())
    }

    @Test
    fun `word chunker holds a half-written word`() {
        val c = WordChunker()
        assertNull(c.feed("It"))
        assertEquals("It was ", c.feed(" was \$1,2"))
        // The money figure goes out only once it is whole.
        assertEquals("\$1,284.17 ", c.feed("84.17 "))
        assertNull(c.feed("tod"))
        assertEquals("tod", c.flush())
        assertNull(c.flush())
    }

    @Test
    fun `spoken text rewrites money, grouped numbers, percent and markdown`() {
        assertEquals("It was 1284 dollars and 17 cents.", SpokenText.forSpeech("It was \$1,284.17."))
        assertEquals("1 dollar", SpokenText.forSpeech("\$1"))
        assertEquals("50 cents", SpokenText.forSpeech("\$0.50"))
        assertEquals("12 dollars", SpokenText.forSpeech("\$12.00"))
        assertEquals("About 25000 people, up 4 percent.", SpokenText.forSpeech("About 25,000 people, up 4%."))
        assertEquals("Milk and eggs", SpokenText.forSpeech("- **Milk** & eggs"))
        assertEquals("3.5 miles at 4:45", SpokenText.forSpeech("3.5 miles at 4:45"))
    }
}
