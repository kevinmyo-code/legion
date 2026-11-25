package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rough-weather line's own rules: when it speaks, and what does not hold it back. */
class RoughWeatherWatchTest {

    private val cooldown = 90 * 60 * 1000L

    @Test
    fun `rough weather is urgent, like the phone's SAFETY category`() {
        assertEquals(ProactiveKind.URGENT, RoughWeatherWatch.KIND)
        // What URGENT buys it: a goodbye keeps it, and a trivia game does not hold it.
        assertTrue(RoughWeatherWatch.KIND.survivesGoodbye)
        assertFalse(RoughWeatherWatch.KIND.heldByGame)
        assertTrue(ProactiveKind.ROUTINE.heldByGame)
    }

    @Test
    fun `a calm to rough transition while driving speaks`() {
        val watch = RoughWeatherWatch()
        assertFalse(watch.onTick(caution = false, driving = true, busy = false, gameActive = false, nowMs = 0))
        assertTrue(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 60_000))
    }

    @Test
    fun `already rough at the first reading never speaks`() {
        val watch = RoughWeatherWatch()
        assertFalse(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 0))
        assertFalse(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 60_000))
    }

    @Test
    fun `parked, the transition is remembered and does not fire later as news`() {
        val watch = RoughWeatherWatch()
        watch.onTick(caution = false, driving = false, busy = false, gameActive = false, nowMs = 0)
        assertFalse(watch.onTick(caution = true, driving = false, busy = false, gameActive = false, nowMs = 60_000))
        // The drive starts with it already rough: that is the greeting's job, not this line's.
        assertFalse(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 120_000))
    }

    @Test
    fun `a running trivia game does not hold it`() {
        val watch = RoughWeatherWatch()
        watch.onTick(caution = false, driving = true, busy = false, gameActive = true, nowMs = 0)
        assertTrue(watch.onTick(caution = true, driving = true, busy = false, gameActive = true, nowMs = 60_000))
    }

    @Test
    fun `a conversation defers it without swallowing the transition`() {
        val watch = RoughWeatherWatch()
        watch.onTick(caution = false, driving = true, busy = false, gameActive = false, nowMs = 0)
        // Mid-turn: nothing is said, and the reading is not taken either.
        assertFalse(watch.onTick(caution = true, driving = true, busy = true, gameActive = false, nowMs = 60_000))
        // The turn ends and it is still news.
        assertTrue(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 120_000))
    }

    @Test
    fun `flickering weather cannot nag inside the cooldown`() {
        val watch = RoughWeatherWatch()
        watch.onTick(caution = false, driving = true, busy = false, gameActive = false, nowMs = 0)
        assertTrue(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 1_000))
        watch.onTick(caution = false, driving = true, busy = false, gameActive = false, nowMs = 2_000)
        assertFalse(watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = 3_000))
        // Past the floor it may speak again.
        watch.onTick(caution = false, driving = true, busy = false, gameActive = false, nowMs = cooldown + 1_000)
        assertTrue(
            watch.onTick(caution = true, driving = true, busy = false, gameActive = false, nowMs = cooldown + 2_000)
        )
    }

    @Test
    fun `no reading yet keeps it silent`() {
        val watch = RoughWeatherWatch()
        assertFalse(watch.onTick(caution = null, driving = true, busy = false, gameActive = false, nowMs = 0))
        assertFalse(watch.onTick(caution = null, driving = true, busy = false, gameActive = false, nowMs = 60_000))
    }
}
