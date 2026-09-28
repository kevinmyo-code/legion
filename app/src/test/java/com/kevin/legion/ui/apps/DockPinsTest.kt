package com.kevin.legion.ui.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DockPins] pinned/unpinned/max-five/move/missing-app, all over plain `List<DockPin>` - no
 * [android.content.SharedPreferences], no Robolectric, per ticket 06's own "unit-tested as pure
 * logic over an in-memory store." [formatDockPins]/[parseDockPins] are pinned separately, since
 * they are the one place a real [android.content.SharedPreferences] value could get corrupted.
 */
class DockPinsTest {

    private val a = DockPin("com.example.a", userSerial = 0)
    private val b = DockPin("com.example.b", userSerial = 0)
    private val c = DockPin("com.example.c", userSerial = 0)
    private val work = DockPin("com.example.a", userSerial = 10) // same package, work profile

    @Test
    fun `pinning an app adds it to the end`() {
        val outcome = DockPins.pin(listOf(a), b)
        assertEquals(DockPins.PinOutcome.Ok(listOf(a, b)), outcome)
    }

    @Test
    fun `pinning an already-pinned app is a no-op, not a second copy`() {
        val outcome = DockPins.pin(listOf(a, b), a)
        assertEquals(DockPins.PinOutcome.Ok(listOf(a, b)), outcome)
    }

    @Test
    fun `the same package in the work profile is a DIFFERENT pin, not the same app`() {
        val outcome = DockPins.pin(listOf(a), work)
        assertEquals(DockPins.PinOutcome.Ok(listOf(a, work)), outcome)
    }

    @Test
    fun `a sixth pin is refused - Full, dock unchanged`() {
        val full = listOf(a, b, c, DockPin("d", 0), DockPin("e", 0))
        val outcome = DockPins.pin(full, DockPin("f", 0))
        assertTrue(outcome is DockPins.PinOutcome.Full)
        assertEquals(full, (outcome as DockPins.PinOutcome.Full).pins)
    }

    @Test
    fun `unpin removes exactly that pin`() {
        assertEquals(listOf(a, c), DockPins.unpin(listOf(a, b, c), b))
    }

    @Test
    fun `unpinning something not pinned is a no-op`() {
        assertEquals(listOf(a, b), DockPins.unpin(listOf(a, b), c))
    }

    @Test
    fun `move right swaps with the next slot`() {
        assertEquals(listOf(b, a, c), DockPins.move(listOf(a, b, c), a, delta = 1))
    }

    @Test
    fun `move left swaps with the previous slot`() {
        assertEquals(listOf(a, c, b), DockPins.move(listOf(a, b, c), c, delta = -1))
    }

    @Test
    fun `moving the rightmost pin right is a no-op, not a wraparound`() {
        assertEquals(listOf(a, b, c), DockPins.move(listOf(a, b, c), c, delta = 1))
    }

    @Test
    fun `moving the leftmost pin left is a no-op`() {
        assertEquals(listOf(a, b, c), DockPins.move(listOf(a, b, c), a, delta = -1))
    }

    @Test
    fun `moving a pin that is not there is a no-op`() {
        assertEquals(listOf(a, b), DockPins.move(listOf(a, b), c, delta = 1))
    }

    @Test
    fun `formatDockPins then parseDockPins round-trips`() {
        val pins = listOf(a, work, b)
        assertEquals(pins, parseDockPins(formatDockPins(pins)))
    }

    @Test
    fun `parseDockPins on an empty string is an empty list`() {
        assertEquals(emptyList<DockPin>(), parseDockPins(""))
    }

    @Test
    fun `parseDockPins drops a malformed entry rather than crashing`() {
        assertEquals(listOf(a), parseDockPins("com.example.a:0,not-a-serial:x,onlyonepart"))
    }
}
