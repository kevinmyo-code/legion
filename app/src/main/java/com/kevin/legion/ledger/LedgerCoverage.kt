package com.kevin.legion.ledger

/**
 * Statement coverage - split out of the old `LedgerProfitAndLoss.kt` when ticket 06
 * (`.scratch/legion-shape/issues/06-budget-versus-actual.md`) replaced the P&L with
 * budget-versus-actual. [AccountCoverage]/[coversMonthWithoutGaps] are named explicitly in that
 * ticket's resolution as REUSED UNCHANGED ("`coversMonthWithoutGaps`... - a budget double-counts a
 * card payment exactly as badly as a P&L did"); [com.kevin.legion.ledger.ProfitAndLoss] and
 * `buildProfitAndLoss` are NOT reused and were deleted along with `ui/ledger/ProfitAndLossSection.kt`
 * - see [BudgetVsActual] for what replaced them.
 */

/**
 * One account's INGESTED coverage of the month being reported on. [coveredFromMs]/[coveredToMs]
 * are the union of every INGESTED [com.kevin.legion.data.local.IngestedFile]'s own
 * `minTxnDate`/`maxTxnDate` overlapping the month for this [accountId] - null only when
 * [accountId] has no INGESTED coverage in range at all, which in practice means it never appears
 * in a coverage list to begin with (see [LedgerController.budgetVsActual]'s construction of this
 * list) rather than showing up with nulls.
 *
 * [coversWholeMonth] is computed by [coversMonthWithoutGaps], a real interval merge - NOT from the
 * earliest `fromMs` and latest `toMs`.
 *
 * That distinction is worth stating plainly, because the min/max version was written first and
 * looks equivalent. It is not: an account with one file covering the 1st to the 10th and another
 * covering the 20th to the 31st has `min = 1st`, `max = 31st`, and a nine-day hole in the middle
 * that min/max cannot see. It would report the month as fully covered while silently missing
 * whatever happened in that week - a check that passes when it should not, which is CLAUDE.md §4
 * rule 6's exact failure shape.
 *
 * It is also NOT the same approximation [LedgerDedup.LedgerCoveredWindow] makes, though it looks
 * like it. That type describes ONE file's own min/max, and for one file that span is a genuine
 * completeness claim - the file passed the reconciliation gate, so within its own dates there is
 * nothing it left out. Stitching SEVERAL files' spans together is a different claim entirely, and
 * it is one no single file's gate underwrites.
 */
data class AccountCoverage(
    val accountId: String,
    val coversWholeMonth: Boolean,
    val coveredFromMs: Long?,
    val coveredToMs: Long?,
)

/**
 * Whether [windows] together cover `[monthStartMs, monthEndMs]` with **no internal gap** - the
 * real check behind [AccountCoverage.coversWholeMonth].
 *
 * Pure, so it is testable without Room. Each element is one INGESTED file's own `(minTxnDate,
 * maxTxnDate)`.
 *
 * **Adjacency tolerance is one day, and it is load-bearing.** Every parser stamps dates with
 * `atStartOfDay(ZoneOffset.UTC)`, so a statement whose last transaction falls on the 10th and the
 * next one whose first falls on the 11th are consecutive, not gapped - there is no missing day
 * between them, only a day boundary. Without the tolerance every pair of back-to-back statements
 * would report a spurious hole, and a warning that fires constantly is one nobody reads. Anything
 * larger than a day apart is a genuine gap and is reported as one.
 *
 * Returns false for an empty [windows], never true: no coverage at all is the least complete a
 * month can be (CLAUDE.md §4 rule 6, same reasoning as [BudgetVsActual.isComplete]'s
 * `isNotEmpty()` guard).
 */
internal fun coversMonthWithoutGaps(
    windows: List<Pair<Long, Long>>,
    monthStartMs: Long,
    monthEndMs: Long,
): Boolean {
    if (windows.isEmpty()) return false

    val sorted = windows.sortedBy { it.first }
    // `reach` is the furthest point covered contiguously so far. It starts at
    // the earliest window's start; if that is already after the month begins,
    // the month's opening days are uncovered and nothing later can fix it.
    if (sorted.first().first > monthStartMs) return false

    var reach = sorted.first().second
    for ((from, to) in sorted.drop(1)) {
        // A window starting more than a day past `reach` leaves a real hole.
        // One entirely inside what is already covered simply does not extend
        // it, which `maxOf` handles without a special case.
        if (from - reach > ADJACENCY_TOLERANCE_MS) return false
        reach = maxOf(reach, to)
    }
    return reach >= monthEndMs
}

/** One day. See [coversMonthWithoutGaps] for why consecutive statements need it. */
private const val ADJACENCY_TOLERANCE_MS = 24L * 60 * 60 * 1000
