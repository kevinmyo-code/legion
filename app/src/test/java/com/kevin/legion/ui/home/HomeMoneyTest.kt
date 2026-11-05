package com.kevin.legion.ui.home

import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.SafeToSpendWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Household-finance 13: the Money panel's words, including the stale-cache wording. */
class HomeMoneyTest {

    @Test
    fun `safe to spend says the figure, until pay, and the top-up with its reason`() {
        val line = safeLine(FinanceRead.Fresh(FinanceFixtures.safe), loading = false)
        assertEquals("-$1,657", line.figure)
        assertEquals(true, line.negative)
        assertEquals("until Nov 20 pay, expected", line.until)
        assertEquals("Top up ~$2,000 by Nov 11: below zero.", line.topUp)
        assertNull(line.note)
    }

    @Test
    fun `a window capped at 30 days does not say pay`() {
        val body = FinanceFixtures.safe.copy(untilReason = "30_days", until = "2026-12-05", topUpNeededCents = 0)
        val line = safeLine(FinanceRead.Fresh(body), loading = false)
        assertEquals("for 30 days, to Dec 5", line.until)
        assertNull(line.topUp)
    }

    @Test
    fun `the saved copy keeps its figure and says its age in words`() {
        val read = FinanceRead.Saved(FinanceFixtures.safe, 0L, "Saved 3 hours ago. The engine could not be reached.")
        val line = safeLine(read, loading = false)
        assertEquals("-$1,657", line.figure)
        assertEquals("Saved 3 hours ago. The engine could not be reached.", line.note)
    }

    @Test
    fun `nothing read and nothing saved says why, never a zero`() {
        val line = safeLine(FinanceRead.Unavailable("The engine could not be reached."), loading = false)
        assertNull(line.figure)
        assertEquals("Couldn't read safe to spend. The engine could not be reached.", line.note)
    }

    @Test
    fun `the engine's own not-available reason is shown as it is`() {
        val body = SafeToSpendWire(available = false, reason = "No checking account is set.")
        val line = safeLine(FinanceRead.Fresh(body), false)
        assertNull(line.figure)
        assertEquals("No checking account is set.", line.note)
    }

    @Test
    fun `the panel says the month's spent of cap and the closest three first`() {
        val panel = homeMoneyPanel(FinanceFixtures.homeFresh)
        assertEquals("November: $1,402 of $2,830", panel.monthLine)
        assertEquals("50%", panel.monthPercent)
        assertEquals(listOf("Dining", "Subscriptions", "Shopping"), panel.bars.take(3).map { it.category })
        assertEquals("November budget, 50% used on day 5", panel.monthUsedLine)
    }

    @Test
    fun `a saved budget copy says so once, not twice when safe to spend says the same`() {
        val note = "Saved 1 hour ago. The engine could not be reached."
        val state = FinanceFixtures.homeFresh.copy(
            safe = FinanceRead.Saved(FinanceFixtures.safe, 0L, note),
            budgets = FinanceRead.Saved(FinanceFixtures.budgets, 0L, note),
        )
        assertNull(homeMoneyPanel(state).budgetNote)
    }

    @Test
    fun `net worth is said partial when the engine lists accounts not in it`() {
        val glance = netWorthGlance(FinanceRead.Fresh(FinanceFixtures.netWorth))
        assertEquals("$148.2k", glance.figure)
        assertEquals("partial: 4 accounts not in it", glance.tag)
    }

    @Test
    fun `the More card's warning is the top-up first, else the first category over`() {
        val panel = homeMoneyPanel(FinanceFixtures.homeFresh)
        assertEquals("Top up ~$2,000 by Nov 11: below zero.", glanceWarning(panel))
        val noTopUp = homeMoneyPanel(
            FinanceFixtures.homeFresh.copy(safe = FinanceRead.Fresh(FinanceFixtures.safe.copy(topUpNeededCents = 0))),
        )
        assertEquals("Dining is over by $14.", glanceWarning(noTopUp))
    }
}
