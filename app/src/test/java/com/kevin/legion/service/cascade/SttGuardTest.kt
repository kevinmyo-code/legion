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
    private fun drop(text: String, voicedMs: Long? = 900) = SttGuard.verdict(text, voicedMs) as? SttGuard.Verdict.Drop

    private fun keeps(text: String, voicedMs: Long? = 900) = SttGuard.verdict(text, voicedMs) is SttGuard.Verdict.Keep

    /** 16 kHz PCM16: [ms] of steady noise at [noise] amplitude, with [bursts] (start ms, length ms) 10x louder. */
    private fun audio(ms: Int, noise: Int = 200, vararg bursts: Pair<Int, Int>): ByteArray {
        val rnd = java.util.Random(7)
        val out = ByteArray(ms * 32)
        for (i in 0 until ms * 16) {
            val atMs = i / 16
            val loud = bursts.any { (from, len) -> atMs in from until from + len }
            val v = ((rnd.nextGaussian() * noise) * if (loud) 10 else 1).toInt().coerceIn(-32768, 32767)
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

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
        assertTrue(SttGuard.verdict("Call Inés", 900L, known = setOf("inés")) is SttGuard.Verdict.Keep)
    }

    @Test
    fun `an English sentence with an aside in another script keeps the English`() {
        val v = SttGuard.verdict("嗯 This truck is the invented Zephyr.", 1_800L)
        assertEquals(SttGuard.Verdict.Keep("This truck is the invented Zephyr."), v)
    }

    @Test
    fun `real short answers and two-word commands are kept whenever the audio carries a voice`() {
        for (t in listOf("5.", "0", "Twenty.", "Yes.", "Sure.", "Zephyr.", "Timer.", "Calendar.", "Do it.", "Stop.", "Navigate.")) {
            assertTrue(t, keeps(t, 300))
        }
        for (t in listOf("Check engine.", "Clear codes.", "Trouble codes?", "Oil change?", "Check calendar.", "Read codes.", "Fuel level?", "Tire pressure?")) {
            assertTrue(t, keeps(t, 600))
        }
        assertTrue("nothing measured (laptop STT, typed): the words stand", keeps("OBD adapter, dongle.", null))
    }

    @Test
    fun `noise is a single word over almost no voice, never two words or a voiced word`() {
        // 3 s of steady road noise the transcriber called a word: silence.
        val hum = audio(3_000)
        assertTrue(SttGuard.voicedMs(hum) < SttGuard.MIN_VOICED_MS)
        for (t in listOf("Uh.", "Yes.", "Bye.")) {
            assertEquals(t, SttGuard.NOISE, (SttGuard.verdict(t, SttGuard.voicedMs(hum)) as? SttGuard.Verdict.Drop)?.reason)
        }
        // Two or more words always stand, whatever the audio scored.
        for (t in listOf("Check engine light.", "Stop the music.", "Add milk to groceries.", "Clear codes.")) {
            assertTrue(t, keeps(t, 0))
        }
        // A real short answer in a loud car scores about 60 ms at 12 dB (review r2): kept from 60 ms on.
        assertTrue(keeps("Yes.", SttGuard.MIN_VOICED_MS))
        assertTrue(keeps("Two.", 59) == false)
        assertEquals(60L, SttGuard.MIN_VOICED_MS)
        // One short word over the same noise, with the turn's pre-roll and end silence around it: kept.
        val word = audio(3_000, 200, 1_200 to 350)
        assertTrue(SttGuard.voicedMs(word) in 300L..400L)
        assertTrue(keeps("Yes.", SttGuard.voicedMs(word)))
        // Level-free: the same shapes 30 dB quieter score the same (this phone's speech is -51 to -74 dBFS).
        assertTrue(SttGuard.voicedMs(audio(3_000, 6, 1_200 to 350)) in 300L..400L)
        assertEquals(setOf(SttGuard.NOISE), SttGuard.SILENT_REASONS)
    }

    @Test
    fun `a stop needs the same voice as any speech, or nothing measured`() {
        assertTrue(SttGuard.stopVoiced(null))
        assertTrue("a quiet stop in road noise", SttGuard.stopVoiced(60))
        assertTrue(!SttGuard.stopVoiced(0))
        assertTrue(!SttGuard.stopVoiced(59))
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
    fun `no voice in the audio is silence, never words`() {
        assertEquals("", CloudSpeechWire.cleanTranscript("<none>"))
        assertEquals("", CloudSpeechWire.cleanTranscript(" <NONE>. "))
        assertEquals("None of them.", CloudSpeechWire.cleanTranscript("None of them."))
        assertEquals("None.", CloudSpeechWire.cleanTranscript("None."))
        assertTrue(CloudSpeechWire.STT_INSTRUCTION.contains(CloudSpeechWire.NO_SPEECH))
        assertTrue(SpokenLanguages.sttInstruction(listOf(SpokenLanguages.ENGLISH) + SpokenLanguages.OFFERED).contains(CloudSpeechWire.NO_SPEECH))
    }
}
