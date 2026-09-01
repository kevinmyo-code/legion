package com.kevin.legion.ui

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.fleet.DueRowView
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildMeterBreaches] is the one new pure function `MetersScreen.kt` adds - see its own doc
 * comment for why only three of the screen's five meters have a breach condition at all. Plain
 * JUnit, no Compose/Android dependency, matching every other pure-builder test in this package
 * ([TodayGapResolversTest] chief among them - `budgetFixture` below mirrors that file's own helper
 * rather than sharing it, since that one is `private` to its class).
 */
class MetersScreenTest {

    private fun budgetFixture(lines: List<BudgetLine>): BudgetVsActual =
        BudgetVsActual(
            entity = LedgerEntity.US,
            month = YearMonth.of(2026, 8),
            lines = lines,
            uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
            coverage = listOf(AccountCoverage("7823", coversWholeMonth = true, coveredFromMs = 0L, coveredToMs = 1L, coveredThroughMs = 1L)),
            excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
        )

    private fun dueRow(overdue: Boolean): DueRowView =
        DueRowView(label = "Oil change", value = "1,200 mi", sub = "every 5,000 mi", overdue = overdue)

    // ---------------------------------------------------------------- no breach

    @Test
    fun `nothing breaches when null budget and no maintenance rows`() {
        assertTrue(buildMeterBreaches(null, emptyList()).isEmpty())
    }

    @Test
    fun `a budget with no target set never breaches - empty is not over`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 0L, actual = 12_000L, gap = -12_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        assertTrue(buildMeterBreaches(budgetFixture(listOf(line)), emptyList()).isEmpty())
    }

    @Test
    fun `under budget across every line does not breach`() {
        val line = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        assertTrue(buildMeterBreaches(budgetFixture(listOf(line)), emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- money

    @Test
    fun `whole-month total over its summed target breaches MONEY`() {
        val line = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val breaches = buildMeterBreaches(budgetFixture(listOf(line)), emptyList())
        assertEquals(1, breaches.size)
        assertEquals("Money", breaches[0].label)
        assertEquals(MetersBreachTarget.MONEY, breaches[0].target)
        assertEquals("over budget by USD 45.00", breaches[0].reason)
    }

    // ---------------------------------------------------------------- groceries

    @Test
    fun `groceries over its own target breaches MONEY_PANTRY separately from the whole-month total`() {
        val groceries = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 60_000L, actual = 70_000L, gap = -10_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val diningWellUnderTarget = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 40_000L, actual = 1_000L, gap = 39_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        // Whole-month total: 71,000 actual of 100,000 target - NOT over, so this must be the ONLY
        // breach reported, proving Money and Groceries are independent checks against independent
        // targets rather than one gap being reported twice.
        val breaches = buildMeterBreaches(budgetFixture(listOf(groceries, diningWellUnderTarget)), emptyList())
        assertEquals(1, breaches.size)
        assertEquals("Groceries", breaches[0].label)
        assertEquals(MetersBreachTarget.MONEY_PANTRY, breaches[0].target)
        assertEquals("over budget by USD 100.00", breaches[0].reason)
    }

    @Test
    fun `a category that is not Groceries never produces a Groceries breach`() {
        val line = BudgetLine(
            category = "Coffee & Snacks",
            gap = PlanGap(target = 5_000L, actual = 9_000L, gap = -4_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val breaches = buildMeterBreaches(budgetFixture(listOf(line)), emptyList())
        // The whole-month total (9,000 of 5,000) IS over, so Money still breaches - only the
        // Groceries-specific breach must be absent.
        assertEquals(listOf(MetersBreachTarget.MONEY), breaches.map { it.target })
    }

    // ---------------------------------------------------------------- maintenance

    @Test
    fun `one overdue maintenance row breaches FLEET, singular wording`() {
        val breaches = buildMeterBreaches(null, listOf(dueRow(overdue = true)))
        assertEquals(1, breaches.size)
        assertEquals("Maintenance", breaches[0].label)
        assertEquals(MetersBreachTarget.FLEET, breaches[0].target)
        assertEquals("1 item overdue", breaches[0].reason)
    }

    @Test
    fun `multiple overdue maintenance rows breach FLEET, plural wording, one row per breach not per item`() {
        val breaches = buildMeterBreaches(null, listOf(dueRow(overdue = true), dueRow(overdue = true), dueRow(overdue = false)))
        assertEquals(1, breaches.size)
        assertEquals("2 items overdue", breaches[0].reason)
    }

    @Test
    fun `no overdue maintenance rows does not breach even when rows exist`() {
        assertTrue(buildMeterBreaches(null, listOf(dueRow(overdue = false), dueRow(overdue = false))).isEmpty())
    }

    // ---------------------------------------------------------------- everything at once

    @Test
    fun `money, groceries and maintenance can all breach together, three rows in order`() {
        val groceries = BudgetLine(
            category = "Groceries",
            gap = PlanGap(target = 60_000L, actual = 70_000L, gap = -10_000L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val dining = BudgetLine(
            category = "Dining Out",
            gap = PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        )
        val breaches = buildMeterBreaches(budgetFixture(listOf(groceries, dining)), listOf(dueRow(overdue = true)))
        assertEquals(listOf("Money", "Groceries", "Maintenance"), breaches.map { it.label })
    }
}
