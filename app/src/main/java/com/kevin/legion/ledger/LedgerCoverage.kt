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
 *
 * [coveredThroughMs] (added 2026-08-18) is a THIRD, more useful reading than either of the above:
 * "good through this date", computed by [coveredThroughMs] the pure function - the end of the
 * unbroken run starting at the month's own start, or null when coverage never reaches the month
 * start at all. Unlike [coveredToMs] it cannot overstate across an internal hole; unlike
 * [coversWholeMonth] it is a date, not a boolean, which is what a driver who already knows the
 * month is not over yet actually wants to see.
 */
data class AccountCoverage(
    val accountId: String,
    val coversWholeMonth: Boolean,
    val coveredFromMs: Long?,
    val coveredToMs: Long?,
    // Defaulted (not just added) so every existing positional/named call site in tests and
    // previews that predates 2026-08-18 keeps compiling unchanged; LedgerController.budgetVsActual
    // is the one production caller and always supplies a real value.
    val coveredThroughMs: Long? = null,
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

/**
 * The end of the UNBROKEN run of coverage beginning at [monthStartMs] - "the figure is good
 * through THIS date", which is a different question from [coversMonthWithoutGaps]'s yes/no and a
 * MORE HONEST one than [AccountCoverage.coveredToMs] (2026-08-18, Kevin: "i know its not eom yet.
 * i just want to know how much ive used so far... just change it here" - replacing a warning he
 * already understood with the date that actually answers his question).
 *
 * **Why not just [AccountCoverage.coveredToMs].** That field is the max of every window's own
 * `toMs`, which overstates exactly when there is an internal hole - two files covering the 1st-10th
 * and the 20th-31st report `coveredToMs` = the 31st, which reads as "good through the 31st" when
 * the 11th-19th is missing entirely. This function walks the SAME windows [coversMonthWithoutGaps]
 * does and stops at the first real gap, so it can never overstate the way the min/max version does
 * - same failure shape CLAUDE.md §4 rule 6 names, applied to a date instead of a boolean.
 *
 * Returns null when coverage never reaches [monthStartMs] at all (empty [windows], or the earliest
 * window starts after the month begins) - there is no honest "through" date to report, and the
 * caller must say so in words rather than printing a date that would read as current. Otherwise
 * returns the reach, capped at [monthEndMs] so a fully-covered month never reports a date past the
 * month's own end.
 */
internal fun coveredThroughMs(
    windows: List<Pair<Long, Long>>,
    monthStartMs: Long,
    monthEndMs: Long,
): Long? {
    if (windows.isEmpty()) return null

    val sorted = windows.sortedBy { it.first }
    // Same "no tolerance on the opening check" posture as coversMonthWithoutGaps: if the
    // earliest window itself starts after the month begins, the days before it are uncovered
    // and there is no "through" date at all, not even the 1st.
    if (sorted.first().first > monthStartMs) return null

    var reach = sorted.first().second
    for ((from, to) in sorted.drop(1)) {
        // A real gap stops the run right here - reach is the last day of the
        // unbroken stretch, not the furthest any later window happens to reach.
        if (from - reach > ADJACENCY_TOLERANCE_MS) break
        reach = maxOf(reach, to)
    }
    return minOf(reach, monthEndMs)
}
