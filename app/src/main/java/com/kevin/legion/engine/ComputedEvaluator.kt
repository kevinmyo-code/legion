package com.kevin.legion.engine

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure functions, no I/O and no database - [RecordStore] gathers the raw values (walking
 * [com.kevin.legion.data.local.EngineRecordDao] and [com.kevin.legion.engine.PayloadCodec]), this
 * object turns them into a [ComputedValue]. Kept separate so ticket 04's rounding and failure
 * rules are unit-testable without Robolectric or a real database.
 */
object ComputedEvaluator {

    /**
     * Money-cents aggregation (ticket 04 answer point 3): SUM/MIN/MAX/LATEST stay cents exactly;
     * AVG rounds HALF_EVEN to the nearest cent (banker's rounding - the same rule a real ledger
     * uses to keep repeated rounding from drifting systematically in one direction); COUNT is a
     * plain integer regardless of type and ignores [values] entirely.
     *
     * [values] must already be in the aggregation's semantic order for [AggregateOp.LATEST] - this
     * function takes the LAST element, it does not itself know what "latest" means for the caller's
     * data; [RecordStore] is the one that sorts children by `updatedAt` before calling this.
     */
    fun aggregateMoneyCents(op: AggregateOp, values: List<Long>): ComputedValue {
        if (op == AggregateOp.COUNT) return ComputedValue.Count(values.size)
        if (values.isEmpty()) return emptyAggregateResult(op)
        return when (op) {
            AggregateOp.SUM -> ComputedValue.MoneyCents(values.sum())
            AggregateOp.AVG -> ComputedValue.MoneyCents(
                BigDecimal(values.sum()).divide(BigDecimal(values.size), 0, RoundingMode.HALF_EVEN).toLong(),
            )
            AggregateOp.MIN -> ComputedValue.MoneyCents(values.min())
            AggregateOp.MAX -> ComputedValue.MoneyCents(values.max())
            AggregateOp.LATEST -> ComputedValue.MoneyCents(values.last())
            AggregateOp.COUNT -> ComputedValue.Count(values.size) // unreachable, guarded above
        }
    }

    /** Same shape as [aggregateMoneyCents] for [com.kevin.legion.data.local.FieldType.NUMBER] /
     * [com.kevin.legion.data.local.FieldType.RATING] source fields - plain `Double` average, no
     * half-even rounding (that rule is specific to money, ticket 04 answer point 3). */
    fun aggregateNumeric(op: AggregateOp, values: List<Double>): ComputedValue {
        if (op == AggregateOp.COUNT) return ComputedValue.Count(values.size)
        if (values.isEmpty()) return emptyAggregateResult(op)
        return when (op) {
            AggregateOp.SUM -> ComputedValue.Number(values.sum())
            AggregateOp.AVG -> ComputedValue.Number(values.sum() / values.size)
            AggregateOp.MIN -> ComputedValue.Number(values.min())
            AggregateOp.MAX -> ComputedValue.Number(values.max())
            AggregateOp.LATEST -> ComputedValue.Number(values.last())
            AggregateOp.COUNT -> ComputedValue.Count(values.size) // unreachable, guarded above
        }
    }

    private fun emptyAggregateResult(op: AggregateOp): ComputedValue = when (op) {
        // SUM/AVG over zero children is a real, correct zero - see ComputedValue.Empty's doc.
        AggregateOp.SUM -> ComputedValue.MoneyCents(0)
        AggregateOp.AVG -> ComputedValue.MoneyCents(0)
        AggregateOp.MIN, AggregateOp.MAX, AggregateOp.LATEST -> ComputedValue.Empty
        AggregateOp.COUNT -> ComputedValue.Count(0)
    }

    /**
     * Same-record arithmetic (ticket 04 answer point 1) over two [com.kevin.legion.data.local.FieldType.MONEY_CENTS]
     * fields. [missingFieldError] is non-null exactly when one of the two source [FieldDef]s no
     * longer exists (ticket 04 answer point 4's "a computed field whose expression references a
     * deleted field") - checked by the caller, since only [RecordStore] knows whether a field id
     * still resolves. A null [left]/[right] with no [missingFieldError] means the field exists but
     * has no value on this particular record, which is also an [ComputedValue.Error], never a
     * fabricated 0.
     */
    fun arithmeticMoneyCents(op: ArithmeticOp, left: Long?, right: Long?, missingFieldError: String?): ComputedValue {
        if (missingFieldError != null) return ComputedValue.Error(missingFieldError)
        if (left == null || right == null) return ComputedValue.Error("a source field has no value on this record")
        return when (op) {
            ArithmeticOp.PLUS -> ComputedValue.MoneyCents(left + right)
            ArithmeticOp.MINUS -> ComputedValue.MoneyCents(left - right)
            ArithmeticOp.TIMES -> ComputedValue.MoneyCents(left * right)
            ArithmeticOp.DIVIDE -> if (right == 0L) {
                ComputedValue.Error("division by zero")
            } else {
                ComputedValue.MoneyCents(
                    BigDecimal(left).divide(BigDecimal(right), 0, RoundingMode.HALF_EVEN).toLong(),
                )
            }
        }
    }

    /** Numeric twin of [arithmeticMoneyCents] for two [com.kevin.legion.data.local.FieldType.NUMBER]
     * fields - plain `Double` arithmetic, no rounding rule (that is money-specific). */
    fun arithmeticNumeric(op: ArithmeticOp, left: Double?, right: Double?, missingFieldError: String?): ComputedValue {
        if (missingFieldError != null) return ComputedValue.Error(missingFieldError)
        if (left == null || right == null) return ComputedValue.Error("a source field has no value on this record")
        return when (op) {
            ArithmeticOp.PLUS -> ComputedValue.Number(left + right)
            ArithmeticOp.MINUS -> ComputedValue.Number(left - right)
            ArithmeticOp.TIMES -> ComputedValue.Number(left * right)
            ArithmeticOp.DIVIDE -> if (right == 0.0) {
                ComputedValue.Error("division by zero")
            } else {
                ComputedValue.Number(left / right)
            }
        }
    }
}
