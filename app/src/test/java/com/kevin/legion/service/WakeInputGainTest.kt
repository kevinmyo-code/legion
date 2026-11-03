package com.kevin.legion.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ticket 20 interim: the fixed input gain ahead of the wake detectors, as a pure unit. */
class WakeInputGainTest {
    @Test
    fun `scales samples by the gain`() {
        val out = WakeInputGain.apply(shortArrayOf(0, 100, -100, 1000), 4, 4)
        assertArrayEquals(shortArrayOf(0, 400, -400, 4000), out)
    }

    @Test
    fun `saturates instead of wrapping`() {
        val out = WakeInputGain.apply(shortArrayOf(10_000, -10_000, Short.MAX_VALUE, Short.MIN_VALUE), 4, 4)
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, Short.MAX_VALUE, Short.MIN_VALUE), out)
    }

    @Test
    fun `only the first n samples and the source stays raw`() {
        val src = shortArrayOf(1, 2, 3, 4)
        val out = WakeInputGain.apply(src, 2, 4)
        assertArrayEquals(shortArrayOf(4, 8), out)
        assertArrayEquals(shortArrayOf(1, 2, 3, 4), src)
    }

    @Test
    fun `gain one is the identity`() {
        val src = shortArrayOf(-32768, -1, 0, 1, 32767)
        assertArrayEquals(src, WakeInputGain.apply(src, src.size, 1))
    }

    @Test
    fun `interim gain is four`() {
        assertEquals(4, WAKE_INPUT_GAIN)
    }
}
