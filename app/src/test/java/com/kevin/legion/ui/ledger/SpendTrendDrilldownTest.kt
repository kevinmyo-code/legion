package com.kevin.legion.ui.ledger

import com.kevin.legion.ledger.MonthSpend
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ticket 04 (quant-viz): pure-logic coverage for [monthlySpendBars], the reconstruction of a HOLE
 * in [MonthSpend]'s own month sequence into a `null` [com.kevin.legion.ui.common.DeckBar] slot.
 * `monthlySpendBars` touches neither Compose nor Room (it is `internal`, plain Kotlin over
 * [YearMonth] arithmetic), so plain JUnit, no Robolectric.
 */
class SpendTrendDrilldownTest {

    @Test
    fun `an interior month absent from trend renders as a null gap slot, not a zero bar`() {
        val trend = listOf(
            MonthSpend(YearMonth.of(2026, 5), totalCents = 412_00L, isComplete = true, hasProvisionalRows = false),
            // June deliberately absent - the gap monthlySpendTrend itself would have left.
            MonthSpend(YearMonth.of(2026, 7), totalCents = 388_50L, isComplete = true, hasProvisionalRows = false),
        )

        val bars = monthlySpendBars(trend)

        assertEquals(3, bars.size) // May, June, July
        assertNull(bars[1]) // June: the hole
        assertEquals(412_00f, bars[0]!!.value)
        assertEquals(388_50f, bars[2]!!.value)
    }

    @Test
    fun `a covered-but-empty month present in trend at zero renders a real zero bar, not a gap`() {
        val trend = listOf(
            MonthSpend(YearMonth.of(2026, 7), totalCents = 0L, isComplete = true, hasProvisionalRows = false),
        )

        val bars = monthlySpendBars(trend)

        assertEquals(1, bars.size)
        assertEquals(0f, bars[0]!!.value)
    }

    @Test
    fun `bar values are the exact Long totalCents carried as Float, no rounding drift at typical fixture sizes`() {
        val trend = listOf(
            MonthSpend(YearMonth.of(2026, 7), totalCents = 184_212L, isComplete = true, hasProvisionalRows = false),
        )

        val bars = monthlySpendBars(trend)

        assertEquals(184_212f, bars.single()!!.value)
    }

    @Test
    fun `every PRESENT month gets a value label, not just the latest`() {
        // 2026-08-16: "spend by month bar chart needs... data labels on each bar as well" - the
        // old latest-month-only behaviour is gone.
        val trend = listOf(
            MonthSpend(YearMonth.of(2026, 5), totalCents = 100_00L, isComplete = true, hasProvisionalRows = false),
            MonthSpend(YearMonth.of(2026, 6), totalCents = 200_00L, isComplete = true, hasProvisionalRows = false),
        )

        val bars = monthlySpendBars(trend)

        assertEquals("100", bars[0]!!.valueLabel)
        assertEquals("200", bars[1]!!.valueLabel)
    }

    @Test
    fun `an absent gap month stays a fully null slot - no valueLabel to gap-fill`() {
        val trend = listOf(
            MonthSpend(YearMonth.of(2026, 5), totalCents = 412_00L, isComplete = true, hasProvisionalRows = false),
            // June deliberately absent.
            MonthSpend(YearMonth.of(2026, 7), totalCents = 388_50L, isComplete = true, hasProvisionalRows = false),
        )

        val bars = monthlySpendBars(trend)

        assertNull(bars[1]) // the whole slot is null, not just its label
    }

    @Test
    fun `an empty trend produces an empty bar list`() {
        assertTrue(monthlySpendBars(emptyList()).isEmpty())
    }

    @Test
    fun `a single-month trend produces one bar spanning nothing else`() {
        val trend = listOf(MonthSpend(YearMonth.of(2026, 8), totalCents = 50_00L, isComplete = false, hasProvisionalRows = true))

        val bars = monthlySpendBars(trend)

        assertEquals(1, bars.size)
        assertEquals(50_00f, bars.single()!!.value)
        assertEquals("50", bars.single()!!.valueLabel)
    }
}
