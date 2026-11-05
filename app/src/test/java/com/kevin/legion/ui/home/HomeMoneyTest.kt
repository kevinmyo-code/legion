package com.kevin.legion.ui.home

import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.SafeToSpendWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Money panel's words, and safe to spend's (kept for the Money view's hidden household sections). */
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
    fun `HOME and the More card read the one budget board, nothing else`() {
        val board = homeMoneyPanel(FinanceFixtures.homeFresh)
        assertEquals("November", board.total?.month)
        assertEquals("$581 of $1,600", board.total?.amountText)
        assertEquals("Dining", board.bars.first().category)
    }

    @Test
    fun `before the first read HOME says it is loading, never a zero`() {
        val board = homeMoneyPanel(HomeMoneyUiState())
        assertNull(board.total)
        assertEquals("Loading budgets.", board.note)
    }
}
