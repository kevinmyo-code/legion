package com.kevin.legion.ui.pantry

import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryReceiptSummary
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The SPEND panel's pure layer (quant-viz ticket 07). No Compose import, on purpose - a plain
 * function of already-fetched [PantryReceiptSummary] rows, unit-testable with no Robolectric and
 * no Canvas, matching [com.kevin.legion.ui.common.DeckChartData.kt]'s own "pure" split. Not built
 * on that file's [com.kevin.legion.ui.common.bucketDailySumCents] - this panel's bars are MONTHLY,
 * not daily, and `ui/common/` is out of scope for this lane (see the ticket's file-ownership list).
 *
 * **A month with no receipts is a GAP, never a zero** (CLAUDE.md §4 rule six's chart-side rule,
 * ticket 07's own wording): groceries were bought that month - people eat - the record just has no
 * ingested receipt for it. A genuine zero-spend month is not a shape this data can express (there
 * is no "I bought nothing" statement the way a bank statement states a covered zero), so every
 * empty month reads as absent, never as `0L`.
 */

/** One calendar month's summed receipt total for the chart currency - `null` totalCents is a GAP month, see file doc. */
data class PantryMonthlyBar(val month: YearMonth, val totalCents: Long?)

/**
 * The single currency [PantrySpendPanel] charts (ticket 07: "chart ONLY the currency with the most
 * receipts; other currencies stay text-total rows"). Ties broken by currency name, deterministically
 * - the exact tie-break is arbitrary but must be STABLE, or the chosen currency would flicker
 * between recompositions/rebuilds on an exact tie. `null` only when [receipts] is empty (nothing to
 * chart at all - [PantrySpendPanel]'s "no receipts ingested" empty state covers that case).
 */
fun chartCurrency(receipts: List<PantryReceiptSummary>): LedgerCurrency? {
    if (receipts.isEmpty()) return null
    return receipts.groupingBy { it.currency }.eachCount().entries
        .sortedWith(compareByDescending<Map.Entry<LedgerCurrency, Int>> { it.value }.thenBy { it.key.name })
        .first().key
}

/**
 * One [PantryMonthlyBar] per calendar month from [receipts]' earliest [currency] receipt through
 * [nowMs]'s own month, inclusive of both ends. Receipts of any OTHER currency are ignored entirely
 * - they stay text-total rows above the chart, per ticket 07.
 *
 * Sums are plain [Long] addition, never routed through [Float]/[Double] (CLAUDE.md §4 rule three) -
 * the geometry-facing [Float] cast happens only at the [com.kevin.legion.ui.common.DeckBar] call
 * site in [PantrySpendPanel], never here.
 *
 * Returns an empty list when [receipts] holds no row of [currency] at all (a degenerate caller
 * error - [chartCurrency] never returns a currency with zero receipts, so this is defensive, not a
 * path any real caller should hit).
 *
 * [zone] defaults to UTC, NOT the device zone, because [PantryReceiptSummary.purchaseDate] is
 * stamped `LocalDate.parse(...).atStartOfDay(ZoneOffset.UTC)` by [com.kevin.legion.pantry
 * .PantryReceiptAgent] - the same convention every ledger parser uses for `txnDate`, and the same
 * bug [com.kevin.legion.ui.ledger.categoryDailySpendBars]'s doc comment names: bucketed in a
 * device zone west of UTC, a receipt dated the 1st (UTC midnight) lands in the PREVIOUS month,
 * every time. The parameter stays overridable only for tests.
 */
fun bucketMonthlySumCents(
    receipts: List<PantryReceiptSummary>,
    currency: LedgerCurrency,
    nowMs: Long,
    zone: ZoneId = ZoneOffset.UTC,
): List<PantryMonthlyBar> {
    val filtered = receipts.filter { it.currency == currency }
    if (filtered.isEmpty()) return emptyList()

    val grouped = filtered.groupBy { YearMonth.from(Instant.ofEpochMilli(it.purchaseDate).atZone(zone)) }
    val firstMonth = grouped.keys.min()
    val nowMonth = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone))
    // A caller passing a nowMs before the earliest receipt (clock skew, a bad fixture) would
    // otherwise generateSequence forever backwards - guard the same degenerate-range shape
    // com.kevin.legion.ui.common.dailyBuckets refuses, by starting from whichever month is
    // earlier and always walking forward to whichever is later.
    val startMonth = if (firstMonth.isBefore(nowMonth)) firstMonth else nowMonth
    val endMonth = if (firstMonth.isBefore(nowMonth)) nowMonth else firstMonth

    val months = generateSequence(startMonth) { it.plusMonths(1) }.takeWhile { !it.isAfter(endMonth) }.toList()
    return months.map { month ->
        val bucket = grouped[month]
        PantryMonthlyBar(month = month, totalCents = if (bucket.isNullOrEmpty()) null else bucket.sumOf { it.totalCents })
    }
}
