package com.kevin.legion.ui.money

import com.kevin.legion.finance.FinanceFixtures.line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Household-finance 13's verification: bar fill maths and the over-cap wording. No pace tick. */
class BudgetBarTest {

    @Test
    fun `the bar is filled to spent over its own cap`() {
        val bar = budgetBarModel(line("Subscriptions", 11_900, 17_000))!!
        assertEquals(0.7f, bar.fill, 0.0001f)
        assertEquals("119 / 170", bar.amountText)
        assertEquals(BarTone.NORMAL, bar.tone)
        assertNull(bar.note)
    }

    @Test
    fun `over the cap fills to the end, takes the warning tone and says over by in words`() {
        val bar = budgetBarModel(line("Dining", 15_400, 14_000))!!
        assertEquals(1f, bar.fill, 0f)
        assertEquals(BarTone.OVER, bar.tone)
        assertEquals("over by $14", bar.note)
    }

    @Test
    fun `near the cap says the percent in words, not only the caution colour`() {
        val bar = budgetBarModel(line("Shopping", 11_000, 13_000))!!
        assertEquals(BarTone.NEAR, bar.tone)
        assertEquals("85% of cap", bar.note)
    }

    @Test
    fun `pending is included and labelled`() {
        val bar = budgetBarModel(line("Groceries", 9_600, 62_000, pending = 3_210))!!
        assertEquals("includes $32 pending", bar.note)
    }

    @Test
    fun `over and pending together say both`() {
        val bar = budgetBarModel(line("Dining", 15_400, 14_000, pending = 500))!!
        assertEquals("over by $14, includes $5 pending", bar.note)
    }

    @Test
    fun `a category with no cap has no bar`() {
        assertNull(budgetBarModel(line("Gifts", 4_000, null)))
    }

    @Test
    fun `closest to cap first, then the rest on track or not in words`() {
        val lines = listOf(
            line("Groceries", 9_600, 62_000), line("Subscriptions", 11_900, 17_000),
            line("Shopping", 6_100, 13_000), line("Pets", 1_200, 9_000), line("Gifts", 4_000, null),
        )
        val bars = closestToCap(lines)
        assertEquals(listOf("Subscriptions", "Shopping", "Groceries", "Pets"), bars.map { it.category })
        assertEquals("+ 1 more on track", moreLine(bars, 3))
        assertNull(moreLine(bars, 4))
        val withOver = closestToCap(lines + line("Dining", 15_400, 14_000))
        assertEquals("+ 5 more, 1 near or over cap", moreLine(withOver, 0))
        assertEquals("+ 3 more on track", moreLine(withOver, 2))
    }

    @Test
    fun `fraction holds to 0 to 1 and a zero cap is empty`() {
        assertEquals(0f, fraction(5, 0), 0f)
        assertEquals(1f, fraction(50, 10), 0f)
        assertEquals(0f, fraction(-5, 10), 0f)
    }
}
