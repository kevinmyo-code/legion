package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.plan.PlanGap
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier
import java.time.YearMonth

/**
 * One entity's monthly budget-versus-actual (`.scratch/legion-shape/issues/06-budget-versus-actual.md`,
 * D9-D13). **Replaces the P&L entirely** - the ticket's resolution names the deletion explicitly
 * ("Deleted: `ProfitAndLossSection` and the `ProfitAndLoss` income/expense/net shape"), and
 * [buildBudgetVsActual] is the direct successor to `buildProfitAndLoss`, not an addition beside
 * it. Pure - no Room, no Android - so it is unit-testable without Robolectric, matching the old
 * P&L build function's shape; [LedgerController.budgetVsActual] is the thin Room-reading wrapper.
 *
 * `Long` cents throughout, never `Double` (CLAUDE.md §4 rule 3).
 */

/**
 * One category's budget line. [gap] is [PlanGap]'s one D5 computation - target minus actual -
 * applied here as budget minus spent: `gap.gap` is what the screen calls "remaining", plain
 * subtraction (D10), never pace-aware or projected.
 *
 * [gap.tier] is [com.kevin.legion.plan.combinedTier] over every contributing row's tier, where a
 * row is [TrustTier.REPORTED] if EITHER it is [IngestMethod.UNRECONCILED] (ticket 02: never faced
 * a reconciliation gate at all) OR its category assignment is still [LedgerTransaction.categoryPending]
 * (ticket 07: an unconfirmed AI guess is a reported claim about this row, per D6/D18) -
 * [PROVEN][TrustTier.PROVEN] only when every contributing row cleared BOTH bars. D6's rule ("one
 * reported actual makes the WHOLE gap reported") is applied here, not reimplemented - see
 * [com.kevin.legion.plan.combinedTier]'s doc comment for where it is expressed exactly once.
 *
 * [hasProvisionalRows] and [hasPendingCategoryGuesses] exist SEPARATELY from [gap.tier] because
 * they are different reasons a driver would want worded differently (D12's "pending, not yet
 * billed" language is not the same claim as "LEGION guessed this category and nobody's confirmed
 * it") - `gap.tier` is the single umbrella signal a screen colours by; these two booleans are what
 * it reads to choose the sentence underneath.
 */
data class BudgetLine(
    val category: String,
    val gap: PlanGap<Long>,
    val hasProvisionalRows: Boolean,
    val hasPendingCategoryGuesses: Boolean,
)

/**
 * D11: uncategorised spend's own loud bucket - "never spread across categories, never hidden,
 * never silently excluded... if it were hidden, every category would look healthy while the total
 * lied." [spentCents] sums every operating expense row with [LedgerTransaction.category] `== null`
 * this month. Deliberately NOT a [BudgetLine] - there is no target to fall short of or come in
 * under here, only an honest "this much left the account with nobody having said what it was for"
 * - and deliberately always present on [BudgetVsActual], even when zero, rather than a caller
 * being trusted to render it conditionally: a screen that skips rendering it AT ZERO can silently
 * become a screen that skips rendering it whenever it stops being zero, the exact failure D11
 * warns against.
 */
data class UncategorizedSpend(val spentCents: Long, val hasProvisionalRows: Boolean)

/**
 * The own-account movements pulled OUT of [BudgetVsActual]'s spend for [month] (Kevin, 2026-08-13 -
 * see [ExclusionReason.OWN_ACCOUNT_MOVEMENT]'s own doc comment for the decision). CLAUDE.md §4 rule
 * 7's "any figure that excluded something discloses it, in words, never a colour or glyph alone"
 * applied to an aggregate rather than a single unreconciled row: [count]/[totalCents] are the words
 * every surface reading this figure must say, and [rows] is what makes that claim inspectable rather
 * than asserted - a driver can always see WHICH rows were pulled out and why, following the same
 * drill-down pattern [UncategorizedSpend] already uses for its own bucket. Deliberately NOT folded
 * into [UncategorizedSpend] - these rows are excluded from spend entirely (never summed into
 * anything, categorised or not), which is a different claim than "spent but nobody said on what".
 */
data class ExcludedOwnAccountMovements(val count: Int, val totalCents: Long, val rows: List<LedgerTransaction>) {
    /** True whenever a screen or the voice path has anything to disclose - the caller's cue to append the caveat sentence at all. */
    val isEmpty: Boolean get() = count == 0
}

/**
 * [coverage]/[isComplete] carry the SAME meaning [ProfitAndLoss.isComplete] used to (ticket 06:
 * "reuse... `coversMonthWithoutGaps`") - D13: "missing coverage is stated in words, next to the
 * number... `coversMonthWithoutGaps` already computes this; it must reach the user's eye, not just
 * the log."
 *
 * [excludedOwnAccountMovements] (2026-08-13) is the disclosure CLAUDE.md §4 rule 7 requires for
 * every figure this data class's [lines]/[uncategorized] feed - see that field's own doc comment.
 */
data class BudgetVsActual(
    val entity: LedgerEntity,
    val month: YearMonth,
    val lines: List<BudgetLine>,
    val uncategorized: UncategorizedSpend,
    val coverage: List<AccountCoverage>,
    val excludedOwnAccountMovements: ExcludedOwnAccountMovements,
) {
    /**
     * Same guard the old P&L's `isComplete` used: an EMPTY [coverage] list must never read as
     * complete. `coverage.all { }` on an empty list is vacuously true, which is a check nothing
     * can fail (CLAUDE.md §4 rule 6) - the explicit `isNotEmpty() &&` is what closes it.
     */
    val isComplete: Boolean get() = coverage.isNotEmpty() && coverage.all { it.coversWholeMonth }
}

/**
 * Ticket 04 (quant-viz): one month's total spend, for [com.kevin.legion.ui.ledger.SpendTrendDrilldown]'s
 * month-over-month bar chart. [totalCents] is [BudgetVsActual.lines]' actuals plus
 * [BudgetVsActual.uncategorized] summed - the SAME "true total" [LedgerBudgetTest]'s own D11 test
 * already recovers by adding every line's actual to the uncategorised bucket, not a parallel figure.
 * [isComplete]/[hasProvisionalRows] mirror [BudgetVsActual]'s own fields for that month, so the
 * trend list can state the identical caveats [BudgetSection] states for the single month it shows.
 *
 * A month is entirely ABSENT from [com.kevin.legion.ledger.LedgerController.monthlySpendTrend]'s
 * returned list when it had zero operating-expense rows AND no ingested coverage at all - see that
 * function's own doc comment for why "no data ingested for this month" and "ingested, and nothing
 * was spent" are different claims (the same gap-vs-zero distinction quant-viz map taste call 3
 * states for daily bars, applied here at month granularity) and why this type carries no sentinel
 * for it - the caller reconstructs the gap from a hole in this list's own month sequence instead.
 */
data class MonthSpend(val month: YearMonth, val totalCents: Long, val isComplete: Boolean, val hasProvisionalRows: Boolean)

/**
 * The pure per-month aggregation/omission rule [LedgerController.monthlySpendTrend] applies to
 * each already-fetched [budget] - extracted so it is unit-testable without Room (`budgetVsActual`
 * itself needs a live [android.content.Context]/DB, this does not). Returns `null` for exactly the
 * "gap month" case [MonthSpend]'s own doc comment describes - zero coverage AND zero total - so the
 * caller's own month-walking loop can omit it with a single `?.let`, never writing a `MonthSpend`
 * that would read as a real zero to something with no way to tell the two apart.
 */
internal fun monthSpendFrom(month: YearMonth, budget: BudgetVsActual): MonthSpend? {
    val totalCents = budget.lines.sumOf { it.gap.actual } + budget.uncategorized.spentCents
    if (budget.coverage.isEmpty() && totalCents == 0L) return null
    val hasProvisionalRows = budget.lines.any { it.hasProvisionalRows } || budget.uncategorized.hasProvisionalRows
    return MonthSpend(month, totalCents, budget.isComplete, hasProvisionalRows)
}

/**
 * CLAUDE.md §4 rule 7's "any figure that excluded something discloses it, in words" sentence for
 * [BudgetVsActual.excludedOwnAccountMovements] - ONE definition, read by both the screen
 * ([com.kevin.legion.ui.ledger.BudgetSection]) and the voice path
 * ([com.kevin.legion.service.LiveToolbox]'s spend answers), the same "state it once so two surfaces
 * cannot diverge" rule [coverageSentence][com.kevin.legion.ui.ledger.coverageSentence] already
 * follows for coverage gaps. Empty-safe (callers are expected to gate on
 * [ExcludedOwnAccountMovements.isEmpty] first, same as every other conditional caveat on this
 * screen, but this never lies if they don't - it just states zero).
 */
fun excludedOwnAccountMovementsSentence(excluded: ExcludedOwnAccountMovements, currency: LedgerCurrency): String {
    if (excluded.isEmpty) return "No own-account movements excluded from spend this month."
    val plural = if (excluded.count == 1) "transaction" else "transactions"
    return "${excluded.count} $plural moving money to your own accounts (${formatMoney(excluded.totalCents, currency)}) " +
        "excluded from spend."
}

/** ticket 02: DETERMINISTIC/LLM_RECONCILED both passed a real reconciliation gate (PROVEN); UNRECONCILED never faced one at all (REPORTED). Ticket 07: a still-pending category guess makes the ROW's contribution to a category total reported too, regardless of what the amount's own ingest method says. */
private fun rowTier(row: LedgerTransaction): TrustTier =
    if (row.ingestMethod == IngestMethod.UNRECONCILED || row.categoryPending) TrustTier.REPORTED else TrustTier.PROVEN

/**
 * The operating expense rows for [entity] over [inPeriod]/[pairingWindow] - same currency filter,
 * transfer exclusion, and expense-only ([LedgerTransaction.amountCents] `< 0`) rule [buildBudgetVsActual]
 * applies, extracted here so a category drill-down (ticket, Kevin 2026-08-07 item 3: "I want to be
 * able to drill down into a category and see the transactions in there") reads the IDENTICAL
 * definition of spend rather than re-deriving it beside this function. Two call sites computing one
 * figure independently and drifting is exactly the bug [AccountBalance.availableCents]'s own doc
 * comment already names for the balance surface - this is that same rule applied to category spend.
 *
 * [ownAccountIds] (2026-08-13) is [entity]'s own set of held accounts, passed straight to
 * [analyzeTransfers] - see [referencesOwnAccount]'s doc comment for why this must be scoped to
 * accounts that have actually had a statement imported, never every account a description merely
 * names.
 */
fun operatingExpenses(
    entity: LedgerEntity,
    inPeriod: List<LedgerTransaction>,
    pairingWindow: List<LedgerTransaction>,
    maxDaysApart: Int = 5,
    ownAccountIds: Set<String> = emptySet(),
): List<LedgerTransaction> {
    val ownCurrencyInPeriod = inPeriod.filter { it.currency == entity.currency }
    val ownCurrencyPairingWindow = pairingWindow.filter { it.currency == entity.currency }
    val analysis = analyzeTransfers(ownCurrencyInPeriod, ownCurrencyPairingWindow, maxDaysApart, ownAccountIds)
    return analysis.operating.filter { it.amountCents < 0 }
}

/**
 * The SAME [analyzeTransfers] call [operatingExpenses] makes, but returning the
 * [ExclusionReason.OWN_ACCOUNT_MOVEMENT] rows it pulled from spend rather than the survivors -
 * [buildBudgetVsActual]'s disclosure source (CLAUDE.md §4 rule 7). Two separate calls into
 * [analyzeTransfers] rather than one shared result threaded through, matching this file's existing
 * posture (`operatingExpenses` is itself a second call site alongside [buildBudgetVsActual]'s own) -
 * [analyzeTransfers] is pure and cheap (no I/O, in-memory list operations only), so the duplication
 * costs nothing and keeps each function's contract legible on its own.
 */
private fun excludedOwnAccountMovements(
    entity: LedgerEntity,
    inPeriod: List<LedgerTransaction>,
    pairingWindow: List<LedgerTransaction>,
    maxDaysApart: Int,
    ownAccountIds: Set<String>,
): ExcludedOwnAccountMovements {
    val ownCurrencyInPeriod = inPeriod.filter { it.currency == entity.currency }
    val ownCurrencyPairingWindow = pairingWindow.filter { it.currency == entity.currency }
    val analysis = analyzeTransfers(ownCurrencyInPeriod, ownCurrencyPairingWindow, maxDaysApart, ownAccountIds)
    // Expense-shaped only (amountCents < 0) - the disclosure's claim is "this much was pulled OUT
    // OF SPEND", and a positive own-account-movement row (money arriving FROM another of Kevin's
    // own accounts) was never counted as spend to begin with (operatingExpenses itself filters to
    // amountCents < 0), so including it here would inflate the caveat beyond what it excluded.
    val rows = analysis.excluded
        .filter { it.reason == ExclusionReason.OWN_ACCOUNT_MOVEMENT && it.txn.amountCents < 0 }
        .map { it.txn }
    // Disclosed as a positive dollar figure, matching UncategorizedSpend.spentCents and every other
    // "how much" caveat this screen states - amountCents is negative on an expense row.
    val totalCents = -rows.sumOf { it.amountCents }
    return ExcludedOwnAccountMovements(count = rows.size, totalCents = totalCents, rows = rows)
}

/**
 * Builds [BudgetVsActual] from already-fetched rows and targets.
 *
 * [inPeriod]/[pairingWindow] are reused UNCHANGED from the old P&L build (ticket 06's resolution:
 * "a budget double-counts a card payment exactly as badly as a P&L did") - [analyzeTransfers]
 * still pulls transfers out before anything is summed into a category, so a card payment between
 * two of the entity's own accounts never inflates a category's spend.
 *
 * [targets] is category -> budget cents for [entity]'s currency, already resolved to [month] by
 * the caller (`BudgetTargetDao.currentTargets`'s "latest effective on or before this month" read -
 * D2's "copy forward" happens there, not in this pure function).
 *
 * **Only expense rows (negative [LedgerTransaction.amountCents]) count against a budget** - D10's
 * "as I spend, I want to see how much I can still use per category" reads spend as money that
 * left, matching how a driver thinks about "how much can I still use". This is also why the old
 * P&L's income/net restatement has no replacement here: ticket 06 §10 is explicit the whole model
 * is "plain subtraction", not a broader income/expense/net shape - that shape is exactly what got
 * deleted.
 *
 * [ownAccountIds] (2026-08-13) is [entity]'s own held-account set, threaded through to both
 * [operatingExpenses] and [excludedOwnAccountMovements] so the total this function sums and the
 * disclosure it attaches ([BudgetVsActual.excludedOwnAccountMovements]) come from the identical
 * [analyzeTransfers] classification - never two independently-derived answers to "was this excluded".
 */
fun buildBudgetVsActual(
    entity: LedgerEntity,
    month: YearMonth,
    inPeriod: List<LedgerTransaction>,
    pairingWindow: List<LedgerTransaction>,
    targets: Map<String, Long>,
    coverage: List<AccountCoverage>,
    maxDaysApart: Int = 5,
    ownAccountIds: Set<String> = emptySet(),
): BudgetVsActual {
    val expenses = operatingExpenses(entity, inPeriod, pairingWindow, maxDaysApart, ownAccountIds)
    val byCategory = expenses.filter { it.category != null }.groupBy { it.category!! }
    val uncategorizedRows = expenses.filter { it.category == null }

    fun buildLine(category: String, targetCents: Long): BudgetLine {
        val rows = byCategory[category].orEmpty()
        val spentCents = -rows.sumOf { it.amountCents } // spend expressed positive; amountCents is negative on an expense row
        return BudgetLine(
            category = category,
            gap = PlanGap(
                target = targetCents,
                actual = spentCents,
                gap = targetCents - spentCents,
                tier = rows.map(::rowTier).combinedTier(),
            ),
            hasProvisionalRows = rows.any { it.ingestMethod == IngestMethod.UNRECONCILED },
            hasPendingCategoryGuesses = rows.any { it.categoryPending },
        )
    }

    val targetedLines = targets.map { (category, targetCents) -> buildLine(category, targetCents) }
    // A category with rows but NO budget set is still real spend that
    // happened - it must not silently vanish because nobody set a number for
    // it yet. Folding it into the uncategorised bucket would misstate what
    // D11 means by "uncategorised" (no category assigned, not "no budget
    // assigned"), so it gets its own line with a zero target instead - "$0
    // budgeted, $42 spent" is an honest, visible statement.
    val untargetedLines = (byCategory.keys - targets.keys).map { category -> buildLine(category, 0L) }

    val uncategorizedSpentCents = -uncategorizedRows.sumOf { it.amountCents }

    return BudgetVsActual(
        entity = entity,
        month = month,
        lines = (targetedLines + untargetedLines).sortedBy { it.category },
        uncategorized = UncategorizedSpend(
            spentCents = uncategorizedSpentCents,
            hasProvisionalRows = uncategorizedRows.any { it.ingestMethod == IngestMethod.UNRECONCILED },
        ),
        coverage = coverage,
        excludedOwnAccountMovements = excludedOwnAccountMovements(entity, inPeriod, pairingWindow, maxDaysApart, ownAccountIds),
    )
}
