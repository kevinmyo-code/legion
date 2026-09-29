package com.kevin.legion.ledger

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/*
 * The budget month (Kevin, 2026-09-29, ruling "b"). Beside [buildBudgetVsActual] in its own file so
 * `LedgerBudget.kt` stays under detekt's per-file function ceiling; [budgetMonthOf] is the one
 * definition, and [buildBudgetVsActual] attaches the [EarlyChargesMoved] disclosure.
 */

/** The category whose early charges count in the next month (Kevin, 2026-09-29). See [budgetMonthOf]. */
const val EARLY_CHARGE_CATEGORY = "Housing"

/** How many trailing days of a month an [EARLY_CHARGE_CATEGORY] outflow may fall on and still
 * count in the next month. */
const val EARLY_CHARGE_TRAILING_DAYS = 3

/** A transaction's own calendar date, UTC - every parser stamps `atStartOfDay(ZoneOffset.UTC)`. */
fun calendarDateOf(txn: LedgerTransaction): LocalDate =
    Instant.ofEpochMilli(txn.txnDate).atZone(ZoneOffset.UTC).toLocalDate()

/**
 * True when [txn] is charged early for the NEXT month: an outflow ([LedgerTransaction.amountCents]
 * `< 0`) whose category is [EARLY_CHARGE_CATEGORY] and whose date is one of the last
 * [EARLY_CHARGE_TRAILING_DAYS] days of its month (day `>= lengthOfMonth - 2`). [LedgerTransaction.category]
 * is the effective category - the server serves overrides applied, and a phone rule only fills nulls.
 */
fun countsInNextMonth(txn: LedgerTransaction): Boolean {
    if (txn.amountCents >= 0 || txn.category != EARLY_CHARGE_CATEGORY) return false
    val date = calendarDateOf(txn)
    return date.dayOfMonth >= date.lengthOfMonth() - (EARLY_CHARGE_TRAILING_DAYS - 1)
}

/**
 * **The budget month of a transaction - THE definition every spend-by-month figure reads (Kevin,
 * 2026-09-29, ruling "b": "Treat a Housing charge in the last 3 days of a month as belonging to the
 * next month.").** Rent is charged to the card a day or so before the month it pays for (2026-07-30,
 * 2026-08-31), so by calendar month September showed no rent and July/August two.
 *
 * Its calendar month, except a [countsInNextMonth] row, which belongs to the next one (December
 * rolls into January of the next year).
 *
 * **A reading rule only.** The row's [LedgerTransaction.txnDate] is never changed or stored
 * differently, and transfer pairing ([analyzeTransfers]) and its window stay on calendar dates -
 * only which month's spend a row is counted in moves. Every surface that states a figure
 * containing or missing a moved row says so in words ([EarlyChargesMoved]).
 */
fun budgetMonthOf(txn: LedgerTransaction): YearMonth {
    val calendar = YearMonth.from(calendarDateOf(txn))
    return if (countsInNextMonth(txn)) calendar.plusMonths(1) else calendar
}

/** The rows of [rows] whose [budgetMonthOf] is [month] - how a month's `inPeriod` is selected. */
fun rowsInBudgetMonth(rows: List<LedgerTransaction>, month: YearMonth): List<LedgerTransaction> =
    rows.filter { budgetMonthOf(it) == month }

/**
 * The rows [budgetMonthOf] moved across [month]'s edges, for the words beside a figure (CLAUDE.md §4
 * rule 7: any figure that excluded or moved something discloses it in words). [countedHere] are
 * dated in the previous month and counted in this one; [countedNextMonth] are dated in this month
 * and counted in the next. Both hold only rows that are actually spend (after transfer pairing, the
 * account filter and not-spending categories), so a sentence never mentions a row no figure counts.
 */
data class EarlyChargesMoved(
    val countedHere: List<LedgerTransaction>,
    val countedNextMonth: List<LedgerTransaction>,
) {
    val isEmpty: Boolean get() = countedHere.isEmpty() && countedNextMonth.isEmpty()

    companion object {
        val NONE = EarlyChargesMoved(emptyList(), emptyList())
    }
}

/** "September", or "January 2027" when [target] is in a different year from [from]. */
private fun monthWords(target: YearMonth, from: YearMonth): String {
    val name = target.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    return if (target.year == from.year) name else "$name ${target.year}"
}

/**
 * The sentences for [moved] beside [month]'s figure, one per row, e.g. "Includes a Housing charge of
 * USD 1,180.63 dated 2026-08-31, counted in September." and "A Housing charge of USD 1,180.63 dated
 * 2026-09-30 counts in October, not here." ONE wording, read by the Money screen, the HOME tile,
 * the voice path, the advisor digest, generated views and the spend trend. Empty list when nothing moved.
 */
fun earlyChargeSentences(moved: EarlyChargesMoved, month: YearMonth, currency: LedgerCurrency): List<String> {
    val here = moved.countedHere.sortedBy { it.txnDate }.map {
        "Includes a $EARLY_CHARGE_CATEGORY charge of ${formatMoney(-it.amountCents, currency)} dated " +
            "${calendarDateOf(it)}, counted in ${monthWords(month, month)}."
    }
    val next = moved.countedNextMonth.sortedBy { it.txnDate }.map {
        "A $EARLY_CHARGE_CATEGORY charge of ${formatMoney(-it.amountCents, currency)} dated " +
            "${calendarDateOf(it)} counts in ${monthWords(budgetMonthOf(it), month)}, not here."
    }
    return here + next
}

/**
 * The rows one month's spend is computed from: [inPeriod] is every [currency] row of [rows] whose
 * [budgetMonthOf] is [month]; [pairingWindow] is the calendar month padded by [pairingDays] each
 * side, exactly as before 2026-09-29 - pairing reads calendar dates, never budget months. Pure, so
 * the controller's fetch and a unit test select a month identically.
 */
fun budgetMonthRows(
    rows: List<LedgerTransaction>,
    currency: LedgerCurrency,
    month: YearMonth,
    pairingDays: Int,
): Pair<List<LedgerTransaction>, List<LedgerTransaction>> {
    val monthStartMs = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val nextMonthStartMs = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val monthEndMs = nextMonthStartMs - 1
    val windowMs = java.time.Duration.ofDays(pairingDays.toLong()).toMillis()
    val ownCurrency = rows.filter { it.currency == currency }
    val pairingWindow = ownCurrency.filter {
        it.txnDate in (monthStartMs - windowMs)..(monthEndMs + windowMs)
    }
    return pairingWindow to rowsInBudgetMonth(ownCurrency, month)
}
