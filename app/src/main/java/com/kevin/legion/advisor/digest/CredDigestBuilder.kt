package com.kevin.legion.advisor.digest

import android.content.Context
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.DigestBuilder
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.goals.GoalProgress
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.operatingExpenses
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.plan.combinedTier
import com.kevin.legion.util.compactDate
import com.kevin.legion.util.documentDateCompact
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The CRED advisor's digest (ticket 16, off ticket 08's answer). Read-only over
 * [com.kevin.legion.ledger.LedgerController]/[com.kevin.legion.data.local.LedgerTransactionDao]/
 * [com.kevin.legion.data.local.BudgetTargetDao] (via [LedgerController.budgetVsActual]) and
 * [com.kevin.legion.data.local.GoalDao] - never writes, never blocks on network.
 *
 * **US entity only, deliberately** - matches `research/cred-playbook.md`'s own scope note ("the SG
 * entity is out of scope per legion-shape") and every other CRED-facing surface in the app
 * ([com.kevin.legion.ui.TodayScreen], `LedgerScreen`, `BudgetSection`, `LiveToolbox`'s
 * `set_budget` all hardcode [LedgerEntity.US] - traced, grepped for `LedgerEntity.US`/`.SG`
 * across the app and found zero SG call sites). **Reasoned**, not a fact stated in ticket 16
 * itself - flagged in the build report.
 *
 * **Window (ticket 08 answer call 3): current period + 3 prior, months for CRED.** Per-category
 * [com.kevin.legion.ledger.BudgetLine]s are shown in full only for the CURRENT month - four months
 * of full category breakdowns would run well past ticket 11's ~293-token neighbourhood for a
 * household with more than a couple of budgeted categories. The 4-month window is instead carried
 * by [spendLine]'s single aggregate total-spend-per-month series plus a trend figure, which is
 * exactly ticket 08's own "older history as a single precomputed trend figure, never rows" for the
 * PRIOR three months, applied one period earlier than FLEET/LOG's window because CRED's per-category
 * row count is naturally larger. **Reasoned**, flagged in the build report.
 *
 * **Coverage-empty is this aspect's "not logged"** ([com.kevin.legion.ledger.AccountCoverage] -
 * empty for the month means no statement has ever been ingested for it): [budgetLine]/
 * [uncategorizedLine]/[merchantsLine] all read [DigestText.notLogged] rather than a real "$0.00"
 * when [BudgetVsActual.coverage] is empty, the CRED-domain analogue of BIO's "0 kcal for an
 * unlogged day" rule (ticket 08's own worked example) - a month nothing was ever imported for is not
 * the same claim as a month that was imported and genuinely had zero spend.
 */
class CredDigestBuilder : DigestBuilder {
    override val aspect = AdvisorAspect.CRED

    override suspend fun build(context: Context): String {
        val db = CarDatabase.getDatabase(context)
        val entity = LedgerEntity.US
        val month = YearMonth.now()
        // current + 3 prior months, newest first (index 0 = this month, per ticket 08's window law).
        val monthly = (0..3).map { m -> LedgerController.budgetVsActual(context, entity, month.minusMonths(m.toLong())) }
        val current = monthly[0]

        val lines = mutableListOf<String>()
        lines += budgetLine(current)
        lines += uncategorizedLine(current)
        lines += provisionalLine(db, entity, month)
        lines += coverageLine(current)
        lines += spendLine(monthly)
        lines += merchantsLine(context, entity, month, current)
        goalLines(db)?.let { lines += it }

        return lines.joinToString("\n")
    }

    /**
     * D10: budget vs actual per category, "remaining" as plain subtraction. One `BUDGET` wire-line
     * per [com.kevin.legion.ledger.BudgetLine], not folded into one - a driver asking "am I on
     * track" needs the per-category gaps distinguishable, matching how the example in ticket 08's
     * answer itself is written ("BUDGET groceries target..."). [line.hasProvisionalRows] marks the
     * whole line unverified (CLAUDE.md §4 rule 7) - an UNRECONCILED row contributing to this
     * category's actual has never faced the reconciliation gate at all.
     */
    private fun budgetLine(current: BudgetVsActual): String {
        if (current.coverage.isEmpty()) return DigestText.line("BUDGET", DigestText.notLogged())
        if (current.lines.isEmpty()) return DigestText.line("BUDGET", "no categorized spend recorded")
        return current.lines.joinToString("\n") { line ->
            val value = "${line.category} target ${formatCents(line.gap.target)} actual ${formatCents(line.gap.actual)} " +
                "remaining ${formatCents(line.gap.gap)}"
            val marked = if (line.hasProvisionalRows) DigestText.unverified(value) else value
            DigestText.withTier(DigestText.line("BUDGET", marked), line.gap.tier)
        }
    }

    /** D11: uncategorised spend's own loud bucket - always present, even at zero (see its own doc comment on [com.kevin.legion.ledger.UncategorizedSpend]). */
    private fun uncategorizedLine(current: BudgetVsActual): String {
        if (current.coverage.isEmpty()) return DigestText.line("UNCATEGORIZED", DigestText.notLogged())
        val u = current.uncategorized
        // Says "excluded from SPEND" only when there IS something excluded (2026-08-15) - at a real
        // zero the phrase would be noise in a token-budgeted digest, and nothing is being withheld.
        val value = "actual ${formatCents(u.spentCents)}" + if (u.spentCents > 0L) " excluded from SPEND" else ""
        val marked = if (u.hasProvisionalRows) DigestText.unverified(value) else value
        val tier = if (u.hasProvisionalRows) TrustTier.REPORTED else TrustTier.PROVEN
        return DigestText.withTier(DigestText.line("UNCATEGORIZED", marked), tier)
    }

    /**
     * Ticket 16: "provisional/`UNRECONCILED` rows counted and marked." Counts every
     * [IngestMethod.UNRECONCILED] row this month regardless of source (a mid-cycle card CSV export
     * with no anchor, ticket 12, AND a voice-logged pending charge, [LedgerTransaction.pendingLoggedAt]
     * - both are [IngestMethod.UNRECONCILED] by construction, see that field's own doc comment for
     * why they share the tag but are counted by different DAO queries elsewhere; this digest wants
     * the total, not the split). A genuine zero here IS a real fact (unlike [budgetLine]'s coverage
     * gate) - this table either has such rows or it does not, there is no "unimported" case for a
     * count over rows already in Room.
     */
    private suspend fun provisionalLine(db: CarDatabase, entity: LedgerEntity, month: YearMonth): String {
        val (start, end) = monthBoundsUtc(month)
        val rows = db.ledgerTransactionDao().getForCurrencyInRange(entity.currency, start, end)
        val count = rows.count { it.ingestMethod == IngestMethod.UNRECONCILED }
        val tier = if (count > 0) TrustTier.REPORTED else TrustTier.PROVEN
        return DigestText.withTier(DigestText.line("PROVISIONAL", "$count row${if (count == 1) "" else "s"}"), tier)
    }

    /**
     * D13: missing coverage stated in words, next to the number - [BudgetVsActual.isComplete] IS
     * that check, this just puts it into words. **2026-08-18 (same rewording as
     * [com.kevin.legion.ui.buildCredTile]):** which accounts still have a gap AND the date the
     * month's spend figures are good through, the minimum of every account's own
     * [com.kevin.legion.ledger.AccountCoverage.coveredThroughMs] - the same "how much so far, as
     * of when I extracted it" answer, not just a bare list of account ids with no date attached.
     * Null when any account never even reaches the month's own start (same guard as the tile's).
     */
    private fun coverageLine(current: BudgetVsActual): String {
        if (current.coverage.isEmpty()) return DigestText.line("COVERAGE", DigestText.notLogged())
        return if (current.isComplete) {
            DigestText.line("COVERAGE", "complete")
        } else {
            val gaps = current.coverage.filter { !it.coversWholeMonth }.map { it.accountId }
            val throughs = current.coverage.map { it.coveredThroughMs }
            val throughNote = if (throughs.isNotEmpty() && throughs.none { it == null }) {
                " - good through ${documentDateCompact(throughs.filterNotNull().min())}"
            } else {
                " - no full-month date yet"
            }
            DigestText.line("COVERAGE", "gaps: ${gaps.joinToString(", ")}$throughNote")
        }
    }

    /**
     * The window's aggregate series (see class doc comment) - categorised operating spend
     * ([BudgetVsActual.spentCents], the ONE definition; the uncategorised bucket is excluded from
     * it since 2026-08-15 and reaches the advisor through [uncategorizedLine]'s own always-present
     * line instead) for each of the 4 months, oldest-to-current trend. Months with no ingested
     * coverage at all are dropped from the series entirely (never rendered as a $0.00 that would
     * misstate "nothing was ever imported" as "nothing was spent").
     */
    private fun spendLine(monthly: List<BudgetVsActual>): String {
        val withData = monthly.withIndex().filter { it.value.coverage.isNotEmpty() }
        if (withData.isEmpty()) return DigestText.line("SPEND", DigestText.notLogged())

        val totals = withData.associate { it.index to monthTotalCents(it.value) }
        val tiers = withData.flatMap { it.value.lines.map { line -> line.gap.tier } +
            (if (it.value.uncategorized.hasProvisionalRows) listOf(TrustTier.REPORTED) else emptyList()) }

        val parts = withData.map { (i, bva) ->
            val label = if (i == 0) "this" else "-${i}mo"
            "$label ${formatCents(totals.getValue(i))}"
        }
        val trend = if (withData.size >= 2) {
            val newestIdx = withData.first().index
            val oldestIdx = withData.last().index
            val delta = totals.getValue(newestIdx) - totals.getValue(oldestIdx)
            val sign = if (delta >= 0) "+" else ""
            " trend $sign${formatCents(delta)}/${oldestIdx - newestIdx}mo"
        } else ""

        return DigestText.withTier(DigestText.line("SPEND", parts.joinToString(" ") + trend), tiers.combinedTier())
    }

    private fun monthTotalCents(bva: BudgetVsActual): Long = bva.spentCents

    /**
     * Ticket 16: "top merchants" - a few named exemplars (ticket 08 answer call 4), never the full
     * transaction list. Reads the same [operatingExpenses] definition [budgetLine]'s own figures sum
     * from, so a merchant total here is never a re-derived approximation of what the category lines
     * already say. **[pairingWindow] is passed the SAME in-month row list as [inPeriod]**, not the
     * padded window [LedgerController.budgetVsActual] itself uses internally (that padding is
     * private to [LedgerController]) - a transfer whose other leg falls just outside the calendar
     * month could be misclassified as a merchant spend here. Reasoned, accepted approximation for a
     * digest line, not a gate-critical figure - flagged in the build report.
     */
    private suspend fun merchantsLine(context: Context, entity: LedgerEntity, month: YearMonth, current: BudgetVsActual): String {
        if (current.coverage.isEmpty()) return DigestText.line("MERCHANTS", DigestText.notLogged())
        val db = CarDatabase.getDatabase(context)
        val (start, end) = monthBoundsUtc(month)
        val rows = db.ledgerTransactionDao().getForCurrencyInRange(entity.currency, start, end)
        val expenses = operatingExpenses(entity, rows, rows)
        if (expenses.isEmpty()) return DigestText.line("MERCHANTS", "none")

        val spends = expenses.groupBy { it.description }
            .map { (merchant, txns) -> Triple(merchant, -txns.sumOf { it.amountCents }, txns.map(::rowTier).combinedTier()) }
            .sortedByDescending { it.second }
            .take(3)
        val value = spends.joinToString(", ") { (merchant, cents, _) -> "$merchant ${formatCents(cents)}" }
        return DigestText.withTier(DigestText.line("MERCHANTS", value), spends.map { it.third }.combinedTier())
    }

    /** Mirrors [com.kevin.legion.ledger.LedgerBudget]'s private `rowTier` exactly (that one is not visible outside its file) - see [com.kevin.legion.plan.TrustTier]'s own doc comment for why a REPORTED row is UNRECONCILED or category-pending. */
    private fun rowTier(row: LedgerTransaction): TrustTier =
        if (row.ingestMethod == IngestMethod.UNRECONCILED || row.categoryPending) TrustTier.REPORTED else TrustTier.PROVEN

    /**
     * Ticket 16: "goal progress for any goal carrying a `metricKey`." Only `savings_balance_cents`
     * (the one CRED-relevant key named in [com.kevin.legion.data.local.Goal]'s own doc comment) is
     * actually computed here - every other/unknown [com.kevin.legion.data.local.Goal.metricKey]
     * still gets its statement/target/deadline stated, just without a computed "current" figure.
     * **Narrow, reasoned scope** - flagged in the build report as covering the one metric this
     * ticket's source material names, not a general metric-projection engine.
     */
    private suspend fun goalLines(db: CarDatabase): String? {
        val goals = db.goalDao().currentGoals(AdvisorAspect.CRED.key)
        if (goals.isEmpty()) return null
        val rendered = goals.map { g ->
            val extras = buildList {
                if (g.targetValue != null) add("target ${g.targetValue}${g.unit?.let { " $it" } ?: ""}")
                if (g.deadlineEpoch != null) add("by ${compactDate(g.deadlineEpoch)}")
            }
            val base = (listOf(g.statement) + extras).joinToString(" ")
            val progress = if (g.metricKey == "savings_balance_cents") GoalProgress.savingsBalanceCents(db) else null
            if (progress != null) {
                // quant-viz ticket 08: this digest line states an absolute cents figure, never a
                // fraction/% - CredDigestBuilderTest pins this exact string. If a percentage is
                // ever wanted here, compute it via GoalProgress.accumulationProgress, the ONE
                // definition the GOALS panel's meter (com.kevin.legion.ui.goals.GoalsPanel)
                // already reads, so digest and screen can never disagree.
                DigestText.withTier(
                    DigestText.line("GOAL", "$base current ${formatCents(progress.first)}"),
                    progress.second,
                )
            } else {
                DigestText.line("GOAL", base)
            }
        }
        return rendered.joinToString("\n")
    }

    /** [YearMonth]'s UTC bounds, matching every parser's `atStartOfDay(ZoneOffset.UTC)` convention - a private copy of [LedgerController]'s own (private, unreachable from here) `monthStartMillis`/`monthEndMillis`. */
    private fun monthBoundsUtc(month: YearMonth): Pair<Long, Long> {
        val start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        return start to end
    }
}
