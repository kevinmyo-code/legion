package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeldLinesTest {

    private val drops = mutableListOf<String>()
    private val onDropped: (String, String) -> Unit = { p, why -> drops += "$p|$why" }

    private fun lines(maxAge: Long = 1_000, capacity: Int = 2, maxFailures: Int = 1) =
        HeldLines(maxAge, capacity, maxFailures)

    @Test fun `a fresh held line is taken once`() {
        val h = lines()
        assertNull(h.hold("a", 0, onDropped = onDropped))
        assertEquals("a", h.takeFresh(500, onDropped)?.prompt)
        assertNull(h.takeFresh(500, onDropped))
        assertEquals(emptyList<String>(), drops)
    }

    @Test fun `a stale line is dropped and reported, never spoken`() {
        val h = lines(maxAge = 1_000)
        h.hold("old", 0, onDropped = onDropped)
        assertNull(h.takeFresh(1_001, onDropped))
        assertEquals(listOf("old|stale after 1001 ms"), drops)
    }

    @Test fun `a stale line ahead of a fresh one does not block it`() {
        val h = lines(maxAge = 1_000, capacity = 3)
        h.hold("old", 0, onDropped = onDropped)
        h.hold("new", 2_000, onDropped = onDropped)
        assertEquals("new", h.takeFresh(2_100, onDropped)?.prompt)
        assertEquals(1, drops.size)
    }

    @Test fun `a full area evicts the oldest and says so`() {
        val h = lines(capacity = 1)
        h.hold("first", 0, onDropped = onDropped)
        h.hold("second", 10, onDropped = onDropped)
        assertEquals(listOf("first|area full"), drops)
        assertEquals("second", h.takeFresh(20, onDropped)?.prompt)
    }

    @Test fun `a line is retried once and dropped on the second failure`() {
        val h = lines(maxFailures = 1)
        assertNull(h.hold("x", 0, failures = 1, onDropped = onDropped))
        assertEquals("failed 2 times", h.hold("x", 0, failures = 2, onDropped = onDropped))
        assertEquals(listOf("x|failed 2 times"), drops)
        assertEquals(1, h.size)
    }

    @Test fun `clear reports every held line`() {
        val h = lines(capacity = 3)
        h.hold("a", 0, onDropped = onDropped)
        h.hold("b", 0, onDropped = onDropped)
        h.clear("session closed", onDropped)
        assertEquals(listOf("a|session closed", "b|session closed"), drops)
        assertEquals(0, h.size)
    }
}
