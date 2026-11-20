@file:Suppress("MaxLineLength") // fixtures read best on one line

package com.kevin.legion.service.tools.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Date ranges said as words, resolved by code on the local date (2026-11-19: "what's due this week"
 * read three different windows from the same words). Made-up dates: Thursday 2026-11-19.
 */
class NamedRangeTest {
    private val thu = LocalDate.of(2026, 11, 19)
    private val sat = LocalDate.of(2026, 11, 21)
    private val sun = LocalDate.of(2026, 11, 22)

    private fun r(word: String, today: LocalDate = thu) = NamedRange.resolve(word, today)

    @Test
    fun `each named range resolves on the local date with the week ending Sunday`() {
        assertEquals(NamedRange.Window("today", thu, thu), r("today"))
        assertEquals(NamedRange.Window("tomorrow", thu.plusDays(1), thu.plusDays(1)), r("tomorrow"))
        assertEquals(NamedRange.Window("this_week", thu, sun), r("this_week"))
        assertEquals(NamedRange.Window("next_7_days", thu, thu.plusDays(6)), r("next_7_days"))
        assertEquals(NamedRange.Window("this_weekend", sat, sun), r("this_weekend"))
        assertEquals(NamedRange.Window("next_week", LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 29)), r("next_week"))
        assertEquals(NamedRange.Window("overdue", thu, thu), r("overdue"))
    }

    @Test
    fun `on a weekend this week and this weekend still end on this Sunday`() {
        assertEquals(NamedRange.Window("this_week", sun, sun), r("this_week", sun))
        assertEquals(NamedRange.Window("this_weekend", sat, sun), r("this_weekend", sat))
        assertEquals(NamedRange.Window("this_weekend", sun, sun), r("this_weekend", sun))
    }

    @Test
    fun `spoken forms are accepted, dates and unknown words are not a named range`() {
        assertEquals("this_week", r("This week")?.name)
        assertEquals("next_7_days", r("next 7 days")?.name)
        assertNull(r("2026-11-21"))
        assertNull(r("someday"))
        assertNull(r(""))
    }

    @Test
    fun `a named range wins over dates sent beside it`() {
        val w = NamedRange.window(from = "this_week", to = "2026-11-20", today = thu)
        assertEquals(NamedRange.Window("this_week", thu, sun), w)
        // A spoken date stays possible: plain dates and day words pass through.
        assertEquals(NamedRange.Window(null, thu, sat), NamedRange.window("2026-11-19", "2026-11-21", thu))
        assertEquals(NamedRange.Window(null, thu.minusDays(1), thu), NamedRange.window("yesterday", "2026-11-19", thu))
        assertNull(NamedRange.window("2026-11-21", "2026-11-19", thu))
        assertNull(NamedRange.window("nonsense", "2026-11-19", thu))
    }
}
