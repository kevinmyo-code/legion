@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SttGuard]: shapes like the 2026-11-17 mis-hearings (invented look-alikes, not his words) are not
 * believed; ordinary English, short or long, is.
 */
class SttGuardTest {
    private fun drop(text: String, ms: Long = 2_000, afterQuestion: Boolean = false) =
        SttGuard.verdict(text, ms, afterQuestion = afterQuestion) as? SttGuard.Verdict.Drop

    private fun keeps(text: String, ms: Long = 2_000, afterQuestion: Boolean = false) =
        SttGuard.verdict(text, ms, afterQuestion = afterQuestion) is SttGuard.Verdict.Keep

    @Test
    fun `spanish and other-script fragments are not believed`() {
        assertEquals("not_english", drop("Oye, mira.")?.reason)
        assertEquals("not_english", drop("¿Qué pasó?")?.reason)
        assertEquals("not_english", drop("dijo que no hay nada para hoy")?.reason)
        assertEquals("not_english_script", drop("嗯")?.reason)
        assertEquals("not_english_script", drop("Привет")?.reason)
    }

    @Test
    fun `english with a Spanish-looking word or an accent is english`() {
        for (s in listOf("Find a café near me", "Call José", "El Paso.", "Play lo fi", "Add jalapeño to the list")) {
            assertTrue(s, keeps(s))
        }
        assertTrue(SttGuard.verdict("Call Inés", 1_500, known = setOf("inés")) is SttGuard.Verdict.Keep)
    }

    @Test
    fun `an English sentence with an aside in another script keeps the English`() {
        val v = SttGuard.verdict("嗯 This truck is the invented Zephyr.", 3_000)
        assertEquals(SttGuard.Verdict.Keep("This truck is the invented Zephyr."), v)
    }

    @Test
    fun `real short answers are kept`() {
        assertTrue(keeps("5.", 3_500))
        assertTrue(keeps("0", 1_100))
        assertTrue(keeps("Twenty.", 3_200))
        assertTrue(keeps("Yes.", 1_500))
        assertTrue("after a question a one-word answer stands", keeps("Sure.", 3_400, afterQuestion = true))
        assertTrue(keeps("Zephyr.", 3_400, afterQuestion = true))
        assertTrue(keeps("Timer.", 1_500))
        assertTrue(keeps("Calendar.", 1_500))
        assertTrue(keeps("Do it.", 3_400))
    }

    @Test
    fun `one word from long audio, not a number and not an answer, is silence`() {
        assertEquals("too_short", drop("Uh.", 3_500)?.reason)
        assertTrue("too_short" in SttGuard.SILENT_REASONS)
    }

    @Test
    fun `the hint is one compact line of words, no names`() {
        val h = SttGuard.hint()
        assertTrue(h.startsWith("Words he often says: OBD"))
        assertTrue(h.length < 300)
        println("STT_HINT_CHARS ${h.length}")
    }

    @Test
    fun `the transcriber is never handed an example number or sentence`() {
        val digitRun = Regex("[0-9]{3,}")
        val texts = listOf(
            CloudSpeechWire.STT_INSTRUCTION,
            SttGuard.hint(),
            SpokenLanguages.sttInstruction(listOf(SpokenLanguages.ENGLISH) + SpokenLanguages.OFFERED),
        )
        for (t in texts) assertTrue(t, !digitRun.containsMatchIn(t))
    }

    @Test
    fun `two or more words made only of the hint's own words were read, not heard, and count as silence`() {
        assertEquals("hint_only", drop("OBD adapter, dongle.")?.reason)
        // Names are not in the hint any more, so a car's name is never dropped as hint.
        val known = setOf("zephyr", "comet")
        assertTrue(SttGuard.verdict("Zephyr. Comet.", 2_000, known) is SttGuard.Verdict.Keep)
        assertTrue(keeps("Clear the codes on the Zephyr."))
        assertTrue("an answer to a question stands", keeps("Oil change, recall.", afterQuestion = true))
        assertTrue("hint_only" in SttGuard.SILENT_REASONS && "not_english" !in SttGuard.SILENT_REASONS)
    }

    @Test
    fun `no voice in the audio is silence, never words`() {
        assertEquals("", CloudSpeechWire.cleanTranscript("<none>"))
        assertEquals("", CloudSpeechWire.cleanTranscript(" <NONE>. "))
        assertEquals("None of them.", CloudSpeechWire.cleanTranscript("None of them."))
        assertEquals("None.", CloudSpeechWire.cleanTranscript("None."))
        assertTrue(CloudSpeechWire.STT_INSTRUCTION.contains(CloudSpeechWire.NO_SPEECH))
        assertTrue(SpokenLanguages.sttInstruction(listOf(SpokenLanguages.ENGLISH) + SpokenLanguages.OFFERED).contains(CloudSpeechWire.NO_SPEECH))
    }
}
