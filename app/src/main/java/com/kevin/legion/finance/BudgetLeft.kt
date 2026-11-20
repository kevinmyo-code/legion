package com.kevin.legion.finance

/**
 * Budget left for one month as HOME's front page says it ("left for November") and as Alfred says
 * it: the engine's day-to-day cap minus its day-to-day spend (`total` of `GET /api/finance/budgets`:
 * capped categories that are not fixed bills, pending charges counted). One sum, here, so a voice
 * answer and the screen cannot disagree.
 */
object BudgetLeft {
    /** Cap minus spent; negative when over. Null when no cap is set: there is then no "left", never a zero. */
    fun cents(b: BudgetsWire): Long? = if (b.total.capCents <= 0L) null else b.total.capCents - b.total.spentCents

    /** Days of the month still to come, from the engine's own day count. */
    fun daysLeft(b: BudgetsWire): Int = (b.daysInMonth - b.daysElapsed).coerceAtLeast(0)
}
