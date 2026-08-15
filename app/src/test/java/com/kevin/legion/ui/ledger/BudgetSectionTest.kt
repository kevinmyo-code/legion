package com.kevin.legion.ui.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.MonthSpend
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
}
