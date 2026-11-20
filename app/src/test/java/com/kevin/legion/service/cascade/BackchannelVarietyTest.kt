@file:Suppress("MaxLineLength") // test names and fixtures read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** More backchannel variety (Kevin, 2026-11-19: "other than mm, can we add more variety as well"). Made-up audio. */
class BackchannelVarietyTest {

    @Test
    fun `nine neutral words, none that promises or claims anything`() {
        assertEquals(listOf("Mm.", "Right.", "Yeah.", "Hmm.", "Okay.", "Mhm.", "Uh-huh.", "Alright.", "Let's see."), Backchannel.LINES)
        // It plays before the transcript exists: wrong before a "didn't catch that" or a refusal.
        for (claim in listOf("Got it.", "I see.", "Sure.", "On it.", "One sec.", "Will do.", "Done.", "Noted.")) {
            assertFalse(claim, claim in Backchannel.LINES)
        }
        for (line in Backchannel.LINES) assertEquals(line, SpokenText.forSpeech(line))
    }

    @Test
    fun `no word repeats within the last five, across nine words`() {
        assertEquals(5, Backchannel.ROTATION)
        val recent = RecentLines(Backchannel.ROTATION)
        val said = mutableListOf<String>()
        repeat(40) { i ->
            val (word, _) = Backchannel.pick(recent, i) { _, _ -> true }!!
            assertFalse("repeat at $i", said.takeLast(Backchannel.ROTATION).contains(word))
            recent.add(word)
            said += word
        }
        assertEquals(Backchannel.LINES.toSet(), said.toSet())
    }

    @Test
    fun `two takes per word, used in turn, only those in memory`() {
        assertEquals(2, Backchannel.TAKES)
        val recent = RecentLines(Backchannel.ROTATION)
        val takes = (0 until 4).map { i -> Backchannel.pick(recent, i) { w, _ -> w == "Mhm." }!! }
        assertTrue(takes.all { it.first == "Mhm." })
        assertEquals(setOf(0, 1), takes.map { it.second }.toSet())
        val onlyFirst = Backchannel.pick(RecentLines(Backchannel.ROTATION), 1) { w, t -> w == "Okay." && t == 0 }
        assertEquals("Okay." to 0, onlyFirst)
        assertNull(Backchannel.pick(RecentLines(Backchannel.ROTATION), 0) { _, _ -> false })
    }

    @Test
    fun `the last word of the last conversation is not first in the next`() {
        val carried = RecentLines(Backchannel.ROTATION, listOf("Yeah."))
        repeat(9) { i -> assertTrue(Backchannel.pick(carried, i) { _, _ -> true }!!.first != "Yeah.") }
    }

    @Test
    fun `edge silence is trimmed before the length gate, so a short word fits`() {
        val rate = 24_000
        fun ms(n: Int) = n * rate / 1000 * 2
        // 250 ms silence, 300 ms voice, 400 ms silence: 950 ms raw, as the cloud voice renders a word.
        val pcm = ByteArray(ms(250)) + loud(ms(300)) + ByteArray(ms(400))
        assertFalse(Backchannel.clipFits(pcm.size.toLong() * 1000 / (rate * 2)))
        val trimmed = Backchannel.trimSilence(pcm, rate)
        val trimmedMs = trimmed.size.toLong() * 1000 / (rate * 2)
        assertTrue("trimmed $trimmedMs", trimmedMs in 300L..300L + 2 * Backchannel.EDGE_PAD_MS)
        assertTrue(Backchannel.clipFits(trimmedMs))
        assertEquals(0, Backchannel.trimSilence(ByteArray(ms(500)), rate).size)
        assertNotNull(trimmed)
    }

    private fun loud(n: Int) = ByteArray(n) { i -> if (i % 2 == 0) 0 else 0x20 }
}
