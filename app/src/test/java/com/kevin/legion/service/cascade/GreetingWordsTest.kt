package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hello's words for the "do not reuse" list: the model's text, as the speaker would say it. */
class GreetingWordsTest {
    @Test
    fun `the written hello is kept without tags or a leading filler`() {
        assertEquals("Well, look who it is.", GreetingWords.of("<chuckle> Well, look who it is."))
        assertEquals("Go on then.", GreetingWords.of("Checking. Go on then."))
        assertEquals("Evening shift again?", GreetingWords.of("  Evening shift again?\n"))
    }

    @Test
    fun `nothing left is nothing kept`() {
        assertNull(GreetingWords.of(""))
        assertNull(GreetingWords.of("  <sigh>  "))
    }
}
