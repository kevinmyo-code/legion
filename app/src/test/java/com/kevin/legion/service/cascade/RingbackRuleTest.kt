package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The ringing before Alfred picks up a call (Kevin, 2026-11-28: "about 3 rings till Alfred picks up"). */
class RingbackRuleTest {

    @Test
    fun `it rings three times even when Alfred is ready at once`() {
        assertTrue(RingbackRule.ringAgain(played = 1, answerReady = true))
        assertTrue(RingbackRule.ringAgain(played = 2, answerReady = true))
        assertFalse("Alfred answers after the third ring", RingbackRule.ringAgain(played = 3, answerReady = true))
    }

    @Test
    fun `it keeps ringing while Alfred is not ready, up to a cap`() {
        assertTrue(RingbackRule.ringAgain(played = 3, answerReady = false))
        assertTrue(RingbackRule.ringAgain(played = RingbackRule.MAX_RINGS - 1, answerReady = false))
        assertFalse(
            "a slow open is never held longer by the ringing",
            RingbackRule.ringAgain(played = RingbackRule.MAX_RINGS, answerReady = false),
        )
    }

    @Test
    fun `three rings hold the pickup for well under a landline's fourteen seconds`() {
        assertTrue(RingbackRule.minimumMs() in 6_000L..10_000L)
    }

    @Test
    fun `one ring is PCM16 mono of its length, never clipping, starting and ending in silence`() {
        val pcm = RingbackRule.tone(rate = 16_000, ms = 1_000)
        assertEquals(16_000 * 2, pcm.size)
        val samples = IntArray(pcm.size / 2) { i ->
            ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toInt()
        }
        val peak = samples.maxOf { abs(it) }
        assertTrue("it is heard", peak > Short.MAX_VALUE / 4)
        assertTrue("it never clips", peak < Short.MAX_VALUE * 6 / 10)
        assertEquals("no click at the start", 0, samples.first())
        assertEquals("no click at the end", 0, samples.last())
    }

    @Test
    fun `the gap between rings is silence of its length`() {
        val gap = RingbackRule.silence(rate = 16_000, ms = 500)
        assertEquals(16_000, gap.size)
        assertTrue(gap.all { it == 0.toByte() })
    }
}
