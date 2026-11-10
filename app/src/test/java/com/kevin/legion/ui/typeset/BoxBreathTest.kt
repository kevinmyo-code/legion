package com.kevin.legion.ui.typeset

import org.junit.Assert.assertEquals
import org.junit.Test

/** Box breathing (Kevin, 2026-11-10): four sides of 4 s, in, hold, out, hold, counted 1 to 4. */
class BoxBreathTest {

    @Test
    fun `the cycle is in, hold, out, hold, four seconds each`() {
        assertEquals(BreathState(BreathPhase.IN, 1, 0f), BoxBreath.at(0))
        assertEquals(BreathPhase.HOLD_FULL, BoxBreath.at(4_000).phase)
        assertEquals(BreathPhase.OUT, BoxBreath.at(8_000).phase)
        assertEquals(BreathPhase.HOLD_EMPTY, BoxBreath.at(12_000).phase)
        assertEquals(BreathState(BreathPhase.IN, 1, 0f), BoxBreath.at(16_000))
    }

    @Test
    fun `the count runs one to four within each side`() {
        assertEquals(listOf(1, 2, 3, 4), listOf(0L, 1_000L, 2_500L, 3_999L).map { BoxBreath.at(it).count })
        assertEquals(4, BoxBreath.at(15_999).count)
    }

    @Test
    fun `the dot goes up the left, across the top, down the right, back along the bottom`() {
        assertEquals(0f to 1f, BoxBreath.dot(BoxBreath.at(0)))
        assertEquals(0f to 0f, BoxBreath.dot(BreathState(BreathPhase.HOLD_FULL, 1, 0f)))
        assertEquals(1f to 0f, BoxBreath.dot(BreathState(BreathPhase.OUT, 1, 0f)))
        assertEquals(1f to 1f, BoxBreath.dot(BreathState(BreathPhase.HOLD_EMPTY, 1, 0f)))
        assertEquals(0f to 0.5f, BoxBreath.dot(BoxBreath.at(2_000)))
    }

    @Test
    fun `reduced motion fills up breathing in and empties breathing out`() {
        assertEquals(0.5f, BoxBreath.fill(BoxBreath.at(2_000)), 1e-6f)
        assertEquals(1f, BoxBreath.fill(BoxBreath.at(5_000)), 1e-6f)
        assertEquals(0.5f, BoxBreath.fill(BoxBreath.at(10_000)), 1e-6f)
        assertEquals(0f, BoxBreath.fill(BoxBreath.at(13_000)), 1e-6f)
    }
}
