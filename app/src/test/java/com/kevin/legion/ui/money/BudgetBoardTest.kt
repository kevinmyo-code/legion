package com.kevin.legion.ui.money

import com.kevin.legion.finance.BudgetTotalWire
import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.finance.FinanceFixtures.line
import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.FixedWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin, 2026-11-05: "how much ive spent this month vs how much left on the budget", as a bar, then
 * "no need for the daily bar": every budget in the total, fixed included, and
 * no pace tick. The total bar's figures and words, the category order, and the fixed categories last.
 */
class BudgetBoardTest {

    private val today = FinanceFixtures.today
    private val budgets = FinanceFixtures.budgets

    @Test
    fun `the total bar is every budget, the fixed caps and what was paid on them included`() {
        val total = budgetTotal(budgets, today)
        assertEquals("November", total.month)
        // Day-to-day 581 of 1,600, plus fixed 1,960 paid of 2,160.
        assertEquals("$2,541 of $3,760", total.amountText)
        assertEquals(254_100f / 376_000f, total.fill, 0.0001f)
        assertEquals(BarTone.NORMAL, total.tone)
        assertFalse(total.over)
    }

    @Test
    fun `under the bar it says what is left, and the pending in it, in words`() {
        val total = budgetTotal(budgets, today)
        assertEquals("$1,219 left, includes $32 pending", total.words)
        assertEquals("November: $2,541 spent, $1,219 left of $3,760, includes $32 pending", total.sentence)
    }

    @Test
    fun `a fixed category with no cap adds nothing, paid or not`() {
        val uncapped = budgets.copy(
            fixed = budgets.fixed + FixedWire("Phone", paid = true, paidCents = 9_000),
        )
        assertEquals("$2,541 of $3,760", budgetTotal(uncapped, today).amountText)
    }

    @Test
    fun `an engine that sends no fixed caps gives the day-to-day total alone, never a false over`() {
        val old = budgets.copy(fixed = budgets.fixed.map { it.copy(capCents = null) })
        val total = budgetTotal(old, today)
        assertEquals("$581 of $1,600", total.amountText)
        assertEquals("$1,019 left, includes $32 pending", total.words)
    }

    @Test
    fun `over the whole budget says over by, in words and the warning tone`() {
        val over = budgets.copy(
            categories = listOf(line("Dining", 170_000, 160_000)),
            total = BudgetTotalWire(spentCents = 170_000, capCents = 160_000, percent = 106, paceCents = 26_667),
            fixed = emptyList(),
        )
        val total = budgetTotal(over, today)
        assertEquals("over by $100", total.words)
        assertTrue(total.over)
        assertEquals(BarTone.OVER, total.tone)
        assertEquals(1f, total.fill, 0f)
        assertEquals("November: $1,700 spent, over by $100 on $1,600", total.sentence)
    }

    @Test
    fun `a month that has ended says under budget, not left`() {
        val october = budgets.copy(
            month = "2026-10",
            categories = listOf(line("Dining", 50_000, 160_000)),
            total = BudgetTotalWire(spentCents = 50_000, capCents = 160_000, percent = 31, paceCents = 160_000),
            fixed = emptyList(),
        )
        assertEquals("$1,100 under budget", budgetTotal(october, today).words)
    }

    @Test
    fun `no caps at all says so, and draws an empty bar`() {
        val none = budgets.copy(
            categories = listOf(line("Gifts", 4_000, null)),
            total = BudgetTotalWire(spentCents = 0, capCents = 0),
            fixed = budgets.fixed.map { it.copy(capCents = null) },
        )
        val total = budgetTotal(none, today)
        assertEquals("$0 spent", total.amountText)
        assertEquals("no caps set yet", total.words)
        assertEquals(0f, total.fill, 0f)
    }

    @Test
    fun `every capped category, highest percent used first, uncapped left out`() {
        val board = budgetBoard(FinanceRead.Fresh(budgets), today)
        assertEquals(
            listOf("Dining", "Household", "Subscriptions", "Shopping", "Health", "Groceries", "Pets", "Transport"),
            board.bars.map { it.category },
        )
    }

    @Test
    fun `the engine's fixed categories go last, paid or not paid in words`() {
        val board = budgetBoard(FinanceRead.Fresh(budgets), today)
        assertEquals(
            listOf(
                FixedLineModel("Housing", "paid, $1,750", paid = true),
                FixedLineModel("Insurance", "paid, $210", paid = true),
                FixedLineModel("Utilities", "not paid yet", paid = false),
            ),
            board.fixed,
        )
    }

    @Test
    fun `a capped line the engine also calls fixed is shown as fixed, not among the bars`() {
        val mixed = budgets.copy(
            categories = budgets.categories + line("Housing", 175_000, 175_000),
            fixed = listOf(FixedWire("Housing", paid = true, paidCents = 175_000)),
        )
        val board = budgetBoard(FinanceRead.Fresh(mixed), today)
        assertFalse(board.bars.any { it.category == "Housing" })
        assertEquals(listOf("Housing"), board.fixed.map { it.category })
    }

    @Test
    fun `a saved copy keeps its bars and says how old it is`() {
        val note = "Saved 3 hours ago. The engine could not be reached."
        val board = budgetBoard(FinanceRead.Saved(budgets, 0L, note), today)
        assertEquals("$2,541 of $3,760", board.total?.amountText)
        assertEquals(note, board.note)
        assertTrue(board.noteIsCaution)
    }

    @Test
    fun `nothing read and nothing saved says why, never a zero`() {
        val board = budgetBoard(FinanceRead.Unavailable("The engine could not be reached."), today)
        assertNull(board.total)
        assertTrue(board.bars.isEmpty())
        assertEquals("Couldn't read budgets. The engine could not be reached.", board.note)
    }
}
