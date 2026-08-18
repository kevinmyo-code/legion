package com.kevin.legion.ui.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.MonthSpend
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * quant-viz ticket 10's two pure mappings - [spendTrendSparklinePoints] (the Money tab's hero
 * sparkline) and the "daily all-category sums = sum of per-category [categoryDailySpendBars]
 * series" invariant the ticket's own verification section calls for. Neither touches Compose or
 * Room, so plain JUnit.
 */
class BudgetSectionTest {

    // ---- spendTrendSparklinePoints: reuses monthlySpendBars' own month-hole mapping -----------

    @Test
    fun `a gap month reaches the sparkline as null, exactly like the bar chart it's derived from`() {
        val trend = listOf(
            MonthSpend(YearMonth.of(2026, 5), totalCents = 412_00L, isComplete = true, hasProvisionalRows = false),
            // June deliberately absent - the gap monthlySpendTrend itself would have left.
            MonthSpend(YearMonth.of(2026, 7), totalCents = 388_50L, isComplete = true, hasProvisionalRows = false),
        )

        val points = spendTrendSparklinePoints(trend)

        assertEquals(3, points.size) // May, June, July
        assertNull(points[1]) // June: the hole
        assertEquals(412_00f, points[0])
        assertEquals(388_50f, points[2])
    }

    @Test
    fun `points are the exact Long totalCents carried as Float, matching monthlySpendBars' own value`() {
        val trend = listOf(MonthSpend(YearMonth.of(2026, 7), totalCents = 184_212L, isComplete = true, hasProvisionalRows = false))
        assertEquals(184_212f, spendTrendSparklinePoints(trend).single())
    }

    @Test
    fun `an empty trend produces an empty point list`() {
        assertTrue(spendTrendSparklinePoints(emptyList()).isEmpty())
    }

    // ---- daily all-category bars: same categoryDailySpendBars, unfiltered, one definition ------

    private val month = YearMonth.of(2026, 7)
    private fun day(dayOfMonth: Int): Long = month.atDay(dayOfMonth).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun txn(dayOfMonth: Int, amountCents: Long, category: String?) = LedgerTransaction(
        id = (dayOfMonth * 100 + amountCents).toString().hashCode().toLong(),
        sourceFile = "s", accountId = "checking", currency = LedgerCurrency.USD,
        txnDate = day(dayOfMonth), description = "ROW", amountCents = amountCents, lineRef = "1",
        ingestMethod = IngestMethod.DETERMINISTIC, category = category,
    )
    private fun fullCoverage() = listOf(
        AccountCoverage("checking", coversWholeMonth = true, coveredFromMs = day(1), coveredToMs = day(month.lengthOfMonth())),
    )

    @Test
    fun `daily all-category sums equal the sum of per-category series for the same month`() {
        val groceries = listOf(txn(1, -1200, "Groceries"), txn(15, -4500, "Groceries"))
        val dining = listOf(txn(1, -800, "Dining Out"))
        val uncategorized = listOf(txn(3, -300, null))
        val allExpenses = groceries + dining + uncategorized

        val allBars = categoryDailySpendBars(allExpenses, month, fullCoverage())
        val perCategorySum = categoryDailySpendBars(groceries, month, fullCoverage()).filterNotNull().sumOf { it.value.toLong() } +
            categoryDailySpendBars(dining, month, fullCoverage()).filterNotNull().sumOf { it.value.toLong() } +
            categoryDailySpendBars(uncategorized, month, fullCoverage()).filterNotNull().sumOf { it.value.toLong() }

        val allSum = allBars.filterNotNull().sumOf { it.value.toLong() }
        assertEquals(perCategorySum, allSum)
        assertEquals(allExpenses.sumOf { kotlin.math.abs(it.amountCents) }, allSum)
    }

    @Test
    fun `a covered day with no rows across any category is a genuine zero, never a gap`() {
        val bars = categoryDailySpendBars(listOf(txn(1, -1000, "Groceries")), month, fullCoverage())
        assertEquals(0f, bars[1]!!.value) // day 2, index 1 - covered, nothing spent that day
    }

    // ---- categorySpendBars: the SPEND hero chart (Kevin, 2026-08-15) ---------------------------

    private fun line(category: String, actualCents: Long, targetCents: Long = 0L, provisional: Boolean = false) =
        BudgetLine(
            category = category,
            gap = PlanGap(target = targetCents, actual = actualCents, gap = targetCents - actualCents, tier = TrustTier.PROVEN),
            hasProvisionalRows = provisional,
            hasPendingCategoryGuesses = false,
        )

    private fun budget(lines: List<BudgetLine>, uncategorizedCents: Long = 0L) = BudgetVsActual(
        entity = LedgerEntity.US,
        month = month,
        lines = lines,
        uncategorized = UncategorizedSpend(spentCents = uncategorizedCents, hasProvisionalRows = false),
        coverage = fullCoverage(),
        excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
    )

    @Test
    fun `the bars sum to exactly the spend figure above them, with uncategorised in neither`() {
        val b = budget(
            lines = listOf(line("Groceries", 41_200L), line("Dining Out", 24_500L)),
            uncategorizedCents = 3_412L,
        )

        val bars = categorySpendBars(b)

        assertEquals(65_700L, bars.sumOf { it.value.toLong() })
        assertEquals(b.spentCents, bars.sumOf { it.value.toLong() })
        assertTrue(bars.none { it.label.contains("ncategoris", ignoreCase = true) })
    }

    @Test
    fun `bars are biggest first and EVERY bar carries a value label`() {
        // 2026-08-16: "I need the data label... on top of all the bars" - the old
        // largest-bar-only behaviour is gone; both bars below must carry one now.
        val bars = categorySpendBars(budget(listOf(line("Dining Out", 10_00L), line("Groceries", 90_00L))))

        assertEquals(listOf("Groceries", "Dining Out"), bars.map { it.label })
        assertEquals("90", bars[0].valueLabel)
        assertEquals("10", bars[1].valueLabel)
    }

    @Test
    fun `a category with a budget but no spend this month draws no bar`() {
        val bars = categorySpendBars(budget(listOf(line("Groceries", 90_00L), line("Pets", 0L, targetCents = 50_00L))))

        assertEquals(listOf("Groceries"), bars.map { it.label })
    }

    @Test
    fun `past the cap the tail folds into OTHER - never truncated, the sum still holds`() {
        val many = (1..9).map { line("Cat$it", it * 1_000L) }

        val bars = categorySpendBars(budget(many), maxBars = 6)

        assertEquals(6, bars.size)
        assertEquals("OTHER 4", bars.last().label)
        // 1+2+3+4 thousand cents folded; nothing dropped.
        assertEquals(10_000L, bars.last().value.toLong())
        assertEquals(many.sumOf { it.gap.actual }, bars.sumOf { it.value.toLong() })
    }

    @Test
    fun `a category holding unreconciled rows carries no mark - the words below the chart carry rule 7, not a glyph - and a target still draws its own tick`() {
        // 2026-08-16: "I don't need the x's on the bars" - the PROVISIONAL cross this chart used
        // to draw is gone; BudgetLineRow's "includes pending transactions not yet on a statement"
        // is the CLAUDE.md §4 rule 7 disclosure now, and it was always the disclosure - the cross
        // was redundant reinforcement, never the only place the fact was said.
        val bars = categorySpendBars(budget(listOf(line("Groceries", 41_200L, targetCents = 60_000L, provisional = true))))

        assertNull(bars.single().mark)
        assertEquals(60_000f, bars.single().targetValue)
    }

    @Test
    fun `a month with no categorised spend has no chart at all`() {
        assertTrue(categorySpendBars(budget(emptyList(), uncategorizedCents = 3_412L)).isEmpty())
    }

    // ---- categorySpendChartData: which category a tapped bar means (Kevin, 2026-08-18) ---------

    @Test
    fun `every bar names the category it was built from, index for index`() {
        val b = budget(lines = listOf(line("Groceries", 41_200L), line("Dining Out", 24_500L), line("Fees", 900L)))

        val data = categorySpendChartData(b)

        assertEquals(data.bars.size, data.categories.size)
        assertEquals(listOf("Groceries", "Dining Out", "Fees"), data.categories)
        // Biggest-first ordering is shared, not re-derived - the pairing is what a tap depends on.
        assertEquals(data.bars.map { it.label }, data.categories)
    }

    @Test
    fun `the folded OTHER bar names no category, because it is several`() {
        // Seven spending categories against a six-bar budget: five named, then one fold.
        val b = budget(
            lines = listOf(
                line("Groceries", 70_000L), line("Dining Out", 60_000L), line("Transport", 50_000L),
                line("Utilities", 40_000L), line("Health", 30_000L), line("Fees", 20_000L),
                line("Shopping", 10_000L),
            )
        )

        val data = categorySpendChartData(b)

        assertEquals(6, data.bars.size)
        assertEquals(6, data.categories.size)
        assertTrue(data.bars.last().label.startsWith("OTHER"))
        // The one index a caller must NOT turn into a category name. "OTHER 2" is not a category
        // and drilling into it would query for one that does not exist.
        assertNull(data.categories.last())
        assertEquals(listOf("Groceries", "Dining Out", "Transport", "Utilities", "Health"), data.categories.dropLast(1))
    }

    @Test
    fun `categorySpendBars stays exactly the bars half, so existing callers are unchanged`() {
        val b = budget(lines = listOf(line("Groceries", 41_200L), line("Dining Out", 24_500L)))

        assertEquals(categorySpendChartData(b).bars, categorySpendBars(b))
    }

    @Test
    fun `no spend at all yields no bars and no categories, never a ragged pair`() {
        val data = categorySpendChartData(budget(lines = emptyList(), uncategorizedCents = 5_000L))

        assertTrue(data.bars.isEmpty())
        assertTrue(data.categories.isEmpty())
    }
}
