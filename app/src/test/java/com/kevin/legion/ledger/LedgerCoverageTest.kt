package com.kevin.legion.ledger

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [coversMonthWithoutGaps] moved out of the old `LedgerProfitAndLossTest.kt` when ticket 06
 * (`.scratch/legion-shape/issues/06-budget-versus-actual.md`) replaced the P&L with
 * budget-versus-actual and split `LedgerProfitAndLoss.kt` into `LedgerCoverage.kt` (this
 * function, reused unchanged) and `LedgerBudget.kt` (the new build function, see
 * [LedgerBudgetTest]). These nine cases are unchanged from the P&L era - the function itself
 * did not change, only what calls it did.
 */
class LedgerCoverageTest {

    @Test
    fun `a single window spanning the whole month covers it`() {
        assertTrue(coversMonthWithoutGaps(listOf(MONTH_START to MONTH_END), MONTH_START, MONTH_END))
    }

    @Test
    fun `two windows with a hole in the middle do NOT cover the month`() {
        // The exact case min/max got wrong: min is the month start, max is the
        // month end, and there is a nine-day hole between them.
        val windows = listOf(
            MONTH_START to (MONTH_START + 9 * DAY_MS),
            (MONTH_START + 19 * DAY_MS) to MONTH_END,
        )
        assertFalse(coversMonthWithoutGaps(windows, MONTH_START, MONTH_END))
    }

    @Test
    fun `two back-to-back windows one day apart DO cover the month`() {
        // Consecutive statements: one ends on day 9, the next starts on day 10.
        // No missing day, only a day boundary - must not report a spurious gap.
        val windows = listOf(
            MONTH_START to (MONTH_START + 9 * DAY_MS),
            (MONTH_START + 10 * DAY_MS) to MONTH_END,
        )
        assertTrue(coversMonthWithoutGaps(windows, MONTH_START, MONTH_END))
    }

    @Test
    fun `a window starting after the month begins does not cover it`() {
        assertFalse(
            coversMonthWithoutGaps(listOf((MONTH_START + DAY_MS * 2) to MONTH_END), MONTH_START, MONTH_END)
        )
    }

    @Test
    fun `a window ending before the month ends does not cover it`() {
        assertFalse(
            coversMonthWithoutGaps(listOf(MONTH_START to (MONTH_END - DAY_MS * 2)), MONTH_START, MONTH_END)
        )
    }

    @Test
    fun `a window fully inside another does not break the merge`() {
        val windows = listOf(
            MONTH_START to MONTH_END,
            MID_MONTH to (MID_MONTH + DAY_MS),
        )
        assertTrue(coversMonthWithoutGaps(windows, MONTH_START, MONTH_END))
    }

    @Test
    fun `input order does not change the answer`() {
        val windows = listOf(
            (MONTH_START + 10 * DAY_MS) to MONTH_END,
            MONTH_START to (MONTH_START + 9 * DAY_MS),
        )
        assertTrue(coversMonthWithoutGaps(windows, MONTH_START, MONTH_END))
    }

    @Test
    fun `no windows at all never reads as covered`() {
        assertFalse(coversMonthWithoutGaps(emptyList(), MONTH_START, MONTH_END))
    }

    companion object {
        private const val MONTH_START = 1_751_328_000_000L // 2025-07-01T00:00:00Z-ish, an arbitrary UTC-midnight-aligned instant
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MID_MONTH = MONTH_START + 15 * DAY_MS
        private const val MONTH_END = MONTH_START + 30 * DAY_MS
    }
}
