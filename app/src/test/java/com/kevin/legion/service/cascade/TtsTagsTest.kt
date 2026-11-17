package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [TtsTags]: tags only where they fit, only on the voice's text. Invented lines. */
class TtsTagsTest {
    private fun speech(s: String, budget: TtsTags.Budget = TtsTags.Budget(), plain: Boolean = false) =
        TtsTags.forSpeech(s, budget, plain)

    @Test
    fun `strip removes every tag and tidies the spaces`() {
        assertEquals("That went well. Enjoy the evening.", TtsTags.strip("That went well. <chuckle> Enjoy the evening."))
        assertEquals("Fine.", TtsTags.strip("Fine <long pause>."))
        assertEquals("No tags here.", TtsTags.strip("No tags here."))
        assertFalse(TtsTags.hasTag(TtsTags.strip("<laugh> <sigh> a <short pause> b")))
    }

    @Test
    fun `only the allowlist reaches the voice, one per sentence`() {
        assertEquals("Well, that happened. <chuckle>", speech("Well, that happened. <chuckle>"))
        assertEquals("Long day <sigh> but done with it.", speech("Long day <sigh> but done with it."))
        assertEquals("Take it easy.", speech("Take it easy. <long pause>"))
        assertEquals("Take it easy.", speech("<breath> Take it easy."))
        assertEquals("Ha <laugh> good one.", speech("Ha <laugh> good one <chuckle>."))
    }

    @Test
    fun `figures, codes, money and bad news never carry a tag`() {
        for (s in listOf(
            "You have 45 dollars left. <chuckle>",
            "The code is P0420 <sigh>.",
            "It's due on the 18th <short pause>.",
            "Sorry, that did not work. <sigh>",
            "Nothing was cleared <chuckle>.",
            "Your quiz is overdue. <laugh>",
        )) {
            assertFalse(s, TtsTags.hasTag(speech(s)))
        }
        assertFalse(TtsTags.hasTag(speech("A fine evening. <chuckle>", plain = true)))
    }

    @Test
    fun `at most two per reply`() {
        val b = TtsTags.Budget()
        assertTrue(TtsTags.hasTag(speech("One. <chuckle>", b)))
        assertTrue(TtsTags.hasTag(speech("Two. <laugh>", b)))
        assertFalse(TtsTags.hasTag(speech("Three. <sigh>", b)))
        assertEquals(0, b.left)
    }
}
