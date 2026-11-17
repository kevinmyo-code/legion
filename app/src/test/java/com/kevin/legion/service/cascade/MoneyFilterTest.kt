@file:Suppress("MaxLineLength") // fixtures: invented amounts read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MoneyFilter]: after a verbatim balance, model sentences with money are dropped. Invented lines. */
class MoneyFilterTest {
    @Test
    fun `money in any form is detected`() {
        for (s in listOf(
            "You have \$2,131.75 left.", "About 2131.75 now.", "That is 45 dollars.", "Fifty bucks on the card.",
            "Twenty-one thirty-one seventy-five, as of tonight.", "Twelve hundred owed.", "Two thousand in checking.",
            "Eleven ninety-five fifty-five.", "Ending four four one zero.", "Seventy-five cents pending.",
        )) {
            assertTrue(s, MoneyFilter.hasMoney(s))
        }
    }

    @Test
    fun `ordinary sentences pass`() {
        for (s in listOf(
            "You have two things due today.", "Your HIST 101 quiz is due tonight at 11:59 PM.", "That's all.",
            "The landlord call was due at six.", "One more thing: the dentist is at three.",
        )) {
            assertFalse(s, MoneyFilter.hasMoney(s))
        }
    }

    @Test
    fun `streaming drops whole money sentences and keeps the rest`() {
        val f = MoneyFilter()
        val out = StringBuilder()
        for (d in listOf("Net cash is twenty-", "one thirty-one. Your quiz ", "is due tonight. And ", "\$85.20 is pending.")) {
            out.append(f.feed(d))
        }
        out.append(f.flush())
        assertEquals("Your quiz is due tonight. ", out.toString())
        assertEquals(2, f.dropped)
    }

    @Test
    fun `a decimal point is not a sentence end`() {
        assertEquals(null, MoneyFilter.sentenceEnd("It is 12.50"))
        assertEquals(6, MoneyFilter.sentenceEnd("Done. Next"))
    }
}
