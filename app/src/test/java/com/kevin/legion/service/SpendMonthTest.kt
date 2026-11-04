package com.kevin.legion.service

import java.time.YearMonth
import org.junit.Assert.assertEquals
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

    @Test
    fun `an unreadable month falls back to the current one and says so`() {
        val (month, note) = SpendMonth.resolve("last month", november)
        assertEquals(november, month)
        assertTrue(note!!, note.contains("November 2026 instead"))
    }

    @Test
    fun `the result names its month and sends any other month to ask_engine`() {
        assertEquals("November 2026", SpendMonth.words(november))
        val other = SpendMonth.otherMonths(november)
        assertTrue(other, other.contains("covers November 2026 only"))
        assertTrue(other, other.contains("October 2026"))
        assertTrue(other, other.contains("ask_engine"))
    }
}
