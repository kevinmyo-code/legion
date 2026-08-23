package com.kevin.legion.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit coverage for [ComputedEvaluator] - no database, no Robolectric, matching ticket 04
 * answer point 2's "materialized on write" being tractable specifically because the arithmetic is
 * this cheap to run in isolation. Covers ticket 04 answer point 3 (money rounds HALF_EVEN, count is
 * a plain integer) and point 4 (a missing/zero-divisor input is a worded [ComputedValue.Error],
 * never a silent zero).
 */
class ComputedEvaluatorTest {

    // ---- money aggregation: rounding -----------------------------------------------------------

    @Test
    fun `avg over money cents rounds half-even at the midpoint down to the even cent`() {
        // 1 and 2 average to 1.5 cents - HALF_EVEN rounds a midpoint to the nearest EVEN value, so
        // 1.5 -> 2 (2 is even), not 1.
        val result = ComputedEvaluator.aggregateMoneyCents(AggregateOp.AVG, listOf(1L, 2L))
        assertEquals(ComputedValue.MoneyCents(2), result)
    }

    @Test
    fun `avg over money cents rounds half-even at the midpoint up when that lands on the even cent`() {
        // 3 and 4 average to 3.5 - HALF_EVEN rounds to 4 (even), not 3.
        val result = ComputedEvaluator.aggregateMoneyCents(AggregateOp.AVG, listOf(3L, 4L))
        assertEquals(ComputedValue.MoneyCents(4), result)
    }

    @Test
    fun `avg over money cents with no remainder is exact`() {
        val result = ComputedEvaluator.aggregateMoneyCents(AggregateOp.AVG, listOf(100L, 200L, 300L))
        assertEquals(ComputedValue.MoneyCents(200), result)
    }

    @Test
    fun `sum min max latest over money cents stay exact cents`() {
        val values = listOf(500L, 100L, 900L)
        assertEquals(ComputedValue.MoneyCents(1500), ComputedEvaluator.aggregateMoneyCents(AggregateOp.SUM, values))
        assertEquals(ComputedValue.MoneyCents(100), ComputedEvaluator.aggregateMoneyCents(AggregateOp.MIN, values))
        assertEquals(ComputedValue.MoneyCents(900), ComputedEvaluator.aggregateMoneyCents(AggregateOp.MAX, values))
        // LATEST takes the LAST element - RecordStore is responsible for supplying values already
        // ordered by recency (updatedAt ascending, so the most recent is last).
        assertEquals(ComputedValue.MoneyCents(900), ComputedEvaluator.aggregateMoneyCents(AggregateOp.LATEST, values))
    }

    @Test
    fun `count ignores the value list entirely and never rounds`() {
        assertEquals(ComputedValue.Count(3), ComputedEvaluator.aggregateMoneyCents(AggregateOp.COUNT, listOf(1L, 2L, 3L)))
        assertEquals(ComputedValue.Count(0), ComputedEvaluator.aggregateMoneyCents(AggregateOp.COUNT, emptyList()))
    }

    // ---- empty-children aggregation: real zero vs genuinely empty -------------------------------

    @Test
    fun `sum and avg over zero children is a real zero, not an error`() {
        assertEquals(ComputedValue.MoneyCents(0), ComputedEvaluator.aggregateMoneyCents(AggregateOp.SUM, emptyList()))
        assertEquals(ComputedValue.MoneyCents(0), ComputedEvaluator.aggregateMoneyCents(AggregateOp.AVG, emptyList()))
    }

    @Test
    fun `min max latest over zero children is Empty, never a fabricated zero`() {
        assertEquals(ComputedValue.Empty, ComputedEvaluator.aggregateMoneyCents(AggregateOp.MIN, emptyList()))
        assertEquals(ComputedValue.Empty, ComputedEvaluator.aggregateMoneyCents(AggregateOp.MAX, emptyList()))
        assertEquals(ComputedValue.Empty, ComputedEvaluator.aggregateMoneyCents(AggregateOp.LATEST, emptyList()))
    }

    // ---- numeric aggregation ----------------------------------------------------------------------

    @Test
    fun `numeric avg is plain division, no half-even rounding rule`() {
        val result = ComputedEvaluator.aggregateNumeric(AggregateOp.AVG, listOf(1.0, 2.0))
        assertEquals(ComputedValue.Number(1.5), result)
    }

    // ---- arithmetic: failure is worded, never a silent zero --------------------------------------

    @Test
    fun `arithmetic on a deleted source field materializes a worded error`() {
        val result = ComputedEvaluator.arithmeticMoneyCents(ArithmeticOp.PLUS, 100L, 200L, "a source field for 'total' was deleted")
        assertTrue(result is ComputedValue.Error)
        assertEquals("a source field for 'total' was deleted", (result as ComputedValue.Error).message)
    }

    @Test
    fun `arithmetic with a missing value on an existing field is also a worded error, not a zero`() {
        val result = ComputedEvaluator.arithmeticMoneyCents(ArithmeticOp.PLUS, null, 200L, null)
        assertTrue(result is ComputedValue.Error)
    }

    @Test
    fun `divide by zero is a worded error, never a crash or an infinity`() {
        val result = ComputedEvaluator.arithmeticMoneyCents(ArithmeticOp.DIVIDE, 100L, 0L, null)
        assertEquals(ComputedValue.Error("division by zero"), result)
    }

    @Test
    fun `plus minus times divide compute correctly on money cents`() {
        assertEquals(ComputedValue.MoneyCents(300), ComputedEvaluator.arithmeticMoneyCents(ArithmeticOp.PLUS, 100L, 200L, null))
        assertEquals(ComputedValue.MoneyCents(-100), ComputedEvaluator.arithmeticMoneyCents(ArithmeticOp.MINUS, 100L, 200L, null))
        assertEquals(ComputedValue.MoneyCents(200), ComputedEvaluator.arithmeticMoneyCents(ArithmeticOp.DIVIDE, 400L, 2L, null))
    }

    @Test
    fun `numeric divide by zero is also a worded error`() {
        val result = ComputedEvaluator.arithmeticNumeric(ArithmeticOp.DIVIDE, 1.0, 0.0, null)
        assertEquals(ComputedValue.Error("division by zero"), result)
    }
}
