package com.kevin.legion.service

import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** get_monthly_spend says which month it read and where every other month is (phone test 2026-11-04). */
class SpendMonthTest {
    private val november = YearMonth.of(2026, 11)

    @Test
    fun `no month reads the current one with no note`() {
        assertEquals(november to null, SpendMonth.resolve("", november))
    }

    @Test
    fun `a YYYY-MM month is read as given`() {
        val (month, note) = SpendMonth.resolve("2026-10", november)
        assertEquals(YearMonth.of(2026, 10), month)
        assertNull(note)
    }

    // 2026-11-19: the words he says are resolved here, on the phone's month, never worked out by the model.
    @Test
    fun `this and last are resolved against the phone's month`() {
        assertEquals(november to null, SpendMonth.resolve("this", november))
        assertEquals(november to null, SpendMonth.resolve("this month", november))
        assertEquals(YearMonth.of(2026, 10) to null, SpendMonth.resolve("last", november))
        assertEquals(YearMonth.of(2026, 10) to null, SpendMonth.resolve("Last Month", november))
        assertEquals(YearMonth.of(2025, 12) to null, SpendMonth.resolve("last", YearMonth.of(2026, 1)))
    }

    @Test
    fun `an unreadable month falls back to the current one and says so`() {
        val (month, note) = SpendMonth.resolve("the month before the wedding", november)
        assertEquals(november, month)
        assertTrue(note!!, note.contains("November 2026 instead"))
    }

    @Test
    fun `the result names its month and says how to read another one`() {
        assertEquals("November 2026", SpendMonth.words(november))
        val other = SpendMonth.otherMonths(november)
        assertTrue(other, other.contains("covers November 2026 only"))
        assertTrue(other, other.contains("month=last"))
        assertFalse(other, other.contains("cannot"))
    }
}
