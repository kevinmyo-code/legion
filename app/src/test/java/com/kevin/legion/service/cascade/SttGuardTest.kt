package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SttGuard]: shapes like the 2026-11-17 mis-hearings (invented look-alikes, not his words) are not
 * believed; ordinary English, short or long, is.
 */
class SttGuardTest {
    private fun drop(text: String, ms: Long = 2_000) = SttGuard.verdict(text, ms) as? SttGuard.Verdict.Drop

    @Test
    fun `spanish and other-script fragments are not believed`() {
        assertEquals("not_english", drop("Cuidado.")?.reason)
        assertEquals("not_english", drop("¿Qué pasó?")?.reason)
        assertEquals("not_english", drop("neutralizó las prioridades.")?.reason)
        assertEquals("not_english_script", drop("嗯")?.reason)
        assertEquals("not_english_script", drop("Привет")?.reason)
    }

    @Test
    fun `an English sentence with an aside in another script keeps the English`() {
        val v = SttGuard.verdict("嗯 This truck is the invented Ranger.", 3_000)
        assertEquals(SttGuard.Verdict.Keep("This truck is the invented Ranger."), v)
    }

    @Test
    fun `far too short for the audio is not believed, a short real answer is`() {
        assertEquals("too_short", drop("0", 1_100)?.reason)
        assertEquals("too_short", drop("Hi.", 6_000)?.reason)
        for (ok in listOf("No.", "Yes.", "Yes, please.", "Bye.")) {
            assertTrue(ok, SttGuard.verdict(ok, 1_500) is SttGuard.Verdict.Keep)
        }
        assertTrue(SttGuard.verdict("2", 500) is SttGuard.Verdict.Keep)
    }

    @Test
    fun `ordinary English passes, an accented name he uses passes`() {
        for (ok in listOf(
            "Can you clear the codes on the Ranger?",
            "The adapter is in the Fit, use that one.",
            "What's on my calendar for tomorrow?",
            "No, I said the café on Main.",
        )) {
            assertTrue(ok, SttGuard.verdict(ok, 3_000) is SttGuard.Verdict.Keep)
        }
        assertTrue(SttGuard.verdict("Call José", 1_500, known = setOf("josé")) is SttGuard.Verdict.Keep)
        assertEquals("not_english", drop("Call José", 1_500)?.reason)
    }

    @Test
    fun `the hint is one compact line, names deduplicated and capped`() {
        val names = listOf("Fit", "fit", "", "Ranger") + (1..20).map { "Name$it" }
        val h = SttGuard.hint(names)
        assertTrue(h.startsWith("Words he often says: OBD"))
        assertEquals(1, Regex("(?i)\\bfit\\b").findAll(h).count())
        assertTrue(h.length < 400)
        // Measured for the report: about 4 characters a token.
        println("STT_HINT_CHARS domain_only=${SttGuard.hint(emptyList()).length} with_12_names=${h.length}")
    }
}
