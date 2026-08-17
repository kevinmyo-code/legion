package com.kevin.legion.ui.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.ExcludedOwnAccountMovements
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.MonthSpend
import com.kevin.legion.ledger.UncategorizedSpend
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckBarLabelRow
import com.kevin.legion.ui.common.DeckMeter
import com.kevin.legion.ui.common.DeckSparkline
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.deckWholeDollarLabel
import com.kevin.legion.util.documentDateCompact
import com.kevin.legion.plan.TrustTier
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.kevin.legion.ledger.maskedAccountLabel

/**
 * `US BUDGET` - `.scratch/legion-shape/issues/06-budget-versus-actual.md`, replacing
 * `ProfitAndLossSection` entirely (deleted, per the ticket's own resolution). Sits where the P&L
 * used to, above [BalancesSection], under its own `SectionHeader("US BUDGET")` at the
 * `ui.LedgerScreen` call site.
 *
 * Follows ticket 08's "Instrument" language throughout - mono numerals via [LegionType.amount],
 * hairlines rather than cards, [com.kevin.legion.ui.theme.LegionSemantics.credit]/`debit`/`estimated`
 * from [LocalLegionSemantics] - the same vocabulary [LedgerTransactionRow]/[AccountBalanceRow]/the
 * old P&L section already used.
 *
 * [budget] is null while the month hasn't loaded yet - the caller gates this section's visibility
 * on having at least one month to show, matching the old P&L section's contract.
 *
 * [onOpenCategory] is the category drill-down (Kevin, 2026-08-07: "I want to be able to drill down
 * into a category and see the transactions in there") - tapping a line or the uncategorised bucket
 * calls back with that category's name, or `null` for the uncategorised bucket, so
 * `ui.LedgerScreen` can open [com.kevin.legion.ui.ledger.CategoryDrilldownScreen] with internal
 * Compose state, the same "no nav-graph argument routes" pattern `ui.NotesScreen` already uses.
 *
 * [onOpenExcludedOwnAccountMovements] (2026-08-13) is the SAME pattern applied to
 * [BudgetVsActual.excludedOwnAccountMovements] - tapping the disclosure row below opens
 * [ExcludedOwnAccountMovementsScreen] so the caveat this section states in words is also
 * inspectable, never just asserted (CLAUDE.md §4 rule 7).
 *
 * [onOpenTrend] (quant-viz ticket 04) opens [com.kevin.legion.ui.ledger.SpendTrendDrilldown] -
 * tapping the `< AUGUST 2026 >` month label itself, the same "no nav-graph argument routes"
 * internal-Compose-state pattern every other drilldown here already uses.
 *
 * **The Money tab's hero graphics (quant-viz ticket 10, Kevin 2026-08-13 map taste call 1:
 * "inline viz across all tabs... it has to be glanceable. i'm not gonna read numbers.").** Two
 * always-on pieces, both gated on [budget] being non-null (item 3 of the ticket - nothing new
 * renders while the section still says "Loading...", same gate the existing `when` block below
 * already uses):
 * - **The spend-trend sparkline** ([spendTrend], up to the last 12 months from
 *   [com.kevin.legion.ledger.LedgerController.monthlySpendTrend]) sits directly under the
 *   `< MONTH >` row. [spendTrendSparklinePoints] reuses [monthlySpendBars]'s own month-hole
 *   mapping rather than inventing a second one - a month [spendTrend] itself omitted (no
 *   coverage, ticket 04's own gap rule) reaches [DeckSparkline] as `null`, never folded into a
 *   zero. Tapping the sparkline OR its caption opens the same [SpendTrendDrilldown] the month
 *   label already does.
 * - **The daily-spend bar strip** ([dailyTransactions], every operating-expense row for [month]
 *   across every category - [com.kevin.legion.ledger.LedgerController.monthOperatingExpenses],
 *   the caller's own load) sits between the sparkline and the budget lines, reusing
 *   [categoryDailySpendBars] (ticket 03) unfiltered rather than a third daily-bucketing function -
 *   "one definition of spend" (map taste call 6) means the SAME [bucketDailySumCents]-backed
 *   gap-vs-zero rule the category drill-down's own chart already draws with. `valueLabel` lands on
 *   the single highest-spend day only, exactly as [categoryDailySpendBars] already does.
 *
 * Both pieces are ADDED next to the existing words below, never replacing them (map taste call 4).
 */
@Composable
fun BudgetSection(
    month: YearMonth,
    budget: BudgetVsActual?,
    spendTrend: List<MonthSpend>?,
    dailyTransactions: List<LedgerTransaction>,
    canGoPrevMonth: Boolean,
    canGoNextMonth: Boolean,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenCategory: (String?) -> Unit,
    onOpenExcludedOwnAccountMovements: () -> Unit,
    onOpenTrend: () -> Unit,
) {
    val sem = LocalLegionSemantics.current

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevMonth, enabled = canGoPrevMonth) {
                Text("<", style = LegionType.stamp, color = if (canGoPrevMonth) MaterialTheme.colorScheme.primary else sem.ghost)
            }
            Text(
                monthLabel(month), style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable(onClick = onOpenTrend),
            )
            TextButton(onClick = onNextMonth, enabled = canGoNextMonth) {
                Text(">", style = LegionType.stamp, color = if (canGoNextMonth) MaterialTheme.colorScheme.primary else sem.ghost)
            }
        }

        // ticket 10: the two hero graphics, gated on `budget != null` exactly as this doc comment
        // states - see item 3's "while budget == null render nothing new".
        if (budget != null) {
            val trend = spendTrend ?: emptyList()
            Spacer(Modifier.height(4.dp))
            DeckSparkline(
                points = spendTrendSparklinePoints(trend),
                modifier = Modifier.clickable(onClick = onOpenTrend),
            )
            Text(
                "spend, last ${trend.size} months - tap month for detail",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.clickable(onClick = onOpenTrend),
            )
            Spacer(Modifier.height(6.dp))
            DeckBarChart(bars = categoryDailySpendBars(dailyTransactions, month, budget.coverage))
            Text(
                "daily spend - days no statement covers are marked, not zero",
                style = LegionType.stamp,
                color = sem.faint,
            )
        }

        when {
            budget == null -> Text(
                "Loading...", style = LegionType.stamp, color = sem.ghost,
                modifier = Modifier.padding(top = 6.dp),
            )
            budget.lines.isEmpty() && budget.allOperatingSpendCents == 0L -> Text(
                "No spending this month.", style = MaterialTheme.typography.bodySmall, color = sem.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
            else -> {
                Spacer(Modifier.height(6.dp))
                for (line in budget.lines) {
                    BudgetLineRow(line, budget.entity, month, onClick = { onOpenCategory(line.category) })
                    Spacer(Modifier.height(6.dp))
                }
                // D11: ALWAYS rendered, never conditionally skipped at zero -
                // see UncategorizedSpend's own doc comment for why "only show
                // it when non-zero" is exactly the failure mode this guards
                // against.
                UncategorizedRow(budget.uncategorized, budget.entity, onClick = { onOpenCategory(null) })
            }
        }

        // 2026-08-13: only-account-movements-leave-spend disclosure (CLAUDE.md §4 rule 7 applied
        // to this aggregate) - stated in words whenever anything was pulled out, next to how many
        // rows and how much, and tappable so the claim is inspectable rather than asserted. Placed
        // next to the coverage caveat below since both are "this figure is not the whole picture"
        // statements about the SAME total.
        if (budget != null && !budget.excludedOwnAccountMovements.isEmpty) {
            Spacer(Modifier.height(6.dp))
            Text(
                com.kevin.legion.ledger.excludedOwnAccountMovementsSentence(budget.excludedOwnAccountMovements, budget.entity.currency),
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
                modifier = Modifier.clickable(onClick = onOpenExcludedOwnAccountMovements),
            )
        }

        // D13: rendered, in words, whenever the month is not fully covered -
        // never a colour or icon alone (CLAUDE.md §4 rule 5's "colour alone
        // is never sufficient" posture applied to a budget instead of a P&L).
        if (budget != null && !budget.isComplete) {
            Spacer(Modifier.height(6.dp))
            // ADVISORY (ticket 13 re-home): an incomplete-month caveat, the same shape as
            // UNRECONCILED - act on this, not a failed gate.
            Text(coverageSentence(budget), style = MaterialTheme.typography.bodySmall, color = sem.estimated)
        }
    }
}

@Composable
private fun BudgetLineRow(line: BudgetLine, entity: LedgerEntity, month: YearMonth, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val color = if (line.gap.tier == TrustTier.REPORTED) sem.estimated else colorForGap(line.gap.gap, sem)

    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(line.category, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${formatMoney(line.gap.actual, entity.currency)} of ${formatMoney(line.gap.target, entity.currency)}",
                    style = LegionType.stamp, color = sem.faint,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                // D10: plain subtraction. gap.gap is target - actual, so a
                // NEGATIVE value here means over budget, not under - the
                // label below says which in words, never colour alone.
                Text(formatMoney(line.gap.gap, entity.currency), style = LegionType.amount, color = color)
                Text(if (line.gap.gap >= 0) "remaining" else "over", style = LegionType.stamp, color = sem.faint)
            }
        }
        // ticket 02 (quant-viz): a DeckMeter under the two text lines above -
        // added, never replacing them (map taste call 4). Guard: no meter
        // against a zero (or negative, though that should not occur) target -
        // a meter with nothing to measure against is a lie the row's own
        // "$X of $0" text already tells honestly in words.
        if (line.gap.target > 0L) {
            Spacer(Modifier.height(4.dp))
            DeckMeter(
                fraction = line.gap.actual.toFloat() / line.gap.target.toFloat(),
                paceFraction = paceFractionFor(month),
            )
        }
        provisionalLabel(line.hasProvisionalRows, line.hasPendingCategoryGuesses)?.let {
            Text(it, style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The green "where you should be by today" tick (ticket 02) - `dayOfMonth / lengthOfMonth` for the
 * CURRENT month, `null` for any past or future month, since a pace tick only means anything on the
 * month that is still being lived. Device-zone "today", matching [com.kevin.legion.ui.common.dailyBuckets]'s
 * own "device zone default" posture for anything that reads "today" off the calendar rather than a
 * stored UTC-stamped transaction date.
 */
private fun paceFractionFor(month: YearMonth, zone: ZoneId = ZoneId.systemDefault()): Float? {
    val today = LocalDate.now(zone)
    if (YearMonth.from(today) != month) return null
    return today.dayOfMonth.toFloat() / month.lengthOfMonth().toFloat()
}

@Composable
private fun UncategorizedRow(uncategorized: UncategorizedSpend, entity: LedgerEntity, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // D11: its own bucket, never folded into a category total and
            // never rendered as if it had a budget line of its own - no
            // "remaining"/"over" label, because there is no target to be
            // over or under. Since 2026-08-15 it is also outside the spend
            // total, which the line underneath states in words.
            Text("Uncategorised", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                formatMoney(uncategorized.spentCents, entity.currency),
                style = LegionType.amount,
                color = if (uncategorized.hasProvisionalRows) sem.estimated else sem.debit,
            )
        }
        Text(
            "not assigned to a category - not counted in spend, and in no budget line above",
            style = LegionType.stamp, color = sem.faint,
        )
        if (uncategorized.hasProvisionalRows) {
            Text("includes pending transactions not yet on a statement", style = LegionType.stamp, color = sem.faint)
        }
    }
}

/**
 * The add-category affordance (Kevin 2026-08-07: "let me add a category, without letting the model
 * invent one" - see [com.kevin.legion.ledger.validateNewCategoryName]'s doc comment for the D14
 * boundary this is a DIFFERENT door from). Placed here, at the foot of the category list
 * [BudgetSection] already renders, per "near where categories are already listed".
 *
 * Local text-field state only - [errorText] is a live signal from the state holder
 * ([com.kevin.legion.ui.LedgerScreen], same "merged into fullState each recomposition" pattern
 * `folder`/`scanState` use), never held here, so a refusal survives a recomposition instead of
 * flashing once and vanishing. [successNonce] bumps only on a CONFIRMED write
 * ([com.kevin.legion.ledger.NewCategoryValidation.Valid]) - the [LaunchedEffect] below clears the
 * typed text only then, so a REJECTED attempt (blank, too long, a case-insensitive duplicate)
 * leaves what the driver typed on screen next to the reason, rather than silently discarding it.
 */
@Composable
fun AddCategoryRow(errorText: String?, successNonce: Int, onAdd: (String) -> Unit) {
    val sem = LocalLegionSemantics.current
    var text by remember { mutableStateOf("") }

    LaunchedEffect(successNonce) { text = "" }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Add a category", style = LegionType.stamp) },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (text.isNotBlank()) onAdd(text) }) {
                Text("ADD", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (errorText != null) {
            // ADVISORY (ticket 13 re-home): a form validation error, not a failed gate.
            Text(errorText, style = LegionType.stamp, color = sem.estimated, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * quant-viz ticket 10: the Money tab's always-on hero sparkline - [spendTrend]'s own month-hole
 * mapping REUSED wholesale from [monthlySpendBars] (never a second gap-vs-present rule invented
 * here), just projected down to the bare `Float?` a [DeckSparkline] wants instead of the full
 * [com.kevin.legion.ui.common.DeckBar] the bar-chart drilldown needs. `internal`, not `private`, so
 * a plain JUnit test can pin "a gap month reaches the sparkline as null, exactly like the bar chart
 * it's derived from" without Compose.
 */
internal fun spendTrendSparklinePoints(spendTrend: List<MonthSpend>): List<Float?> =
    monthlySpendBars(spendTrend).map { it?.value }

/**
 * **The SPEND hero's own chart (Kevin, 2026-08-15: "spend hero visual should be bar chart of
 * categories for the month minus the uncategorized").** One bar per category that actually spent
 * something this month, biggest first, uncategorised never among them - the chart is a picture of
 * [BudgetVsActual.spentCents], and its bars sum to exactly that figure, which is the invariant
 * [BudgetSectionTest] pins. A category the driver budgeted but did not spend from this month draws
 * no bar: this is a chart of where money went, and its own zero is already stated in words on the
 * BUDGET drilldown's [BudgetLineRow] ("$0 of $400 - remaining").
 *
 * **Nothing is ever dropped.** Past [maxBars] categories the tail is FOLDED into a single `OTHER`
 * bar carrying the remainder's exact cents and its own count, never truncated away - a chart whose
 * bars silently stopped summing to the hero above it would be the same lie in picture form that
 * CLAUDE.md §4 rule 7 refuses in words. The fold exists only because bar labels stop being legible
 * past roughly six columns at phone width, not because the tail is uninteresting.
 *
 * [DeckBar.targetValue] carries the category's own budget when one is set (the kit's amber dashed
 * tick), so the hero reads as spend-against-target per category, not just relative sizes. `OTHER`
 * carries none: the folded lines' targets are a mix of set and unset, and summing them would draw a
 * tick that understates itself.
 *
 * **Every bar carries [DeckBar.valueLabel]** (Kevin, 2026-08-16: "I need the data label... on top
 * of all the bars just like groceries" - the pre-existing behaviour, largest-bar-only, was a
 * leftover of the chart's first cut, not a deliberate design). [deckWholeDollarLabel], not
 * [com.kevin.legion.ledger.formatCents] - see that function's own doc for why a bar-top label
 * needs a different precision than a list row.
 *
 * **[DeckBar.mark] is always `null` here now** (same date, "I don't need the x's on the bars" -
 * the [com.kevin.legion.ui.common.DeckMarkerType.PROVISIONAL] cross this used to draw on a
 * category holding [com.kevin.legion.data.local.IngestMethod.UNRECONCILED] rows). This is safe to
 * drop, and ONLY safe to drop, because [BudgetLineRow] already states the same fact in words
 * directly beneath this chart for every affected category ("includes pending transactions not yet
 * on a statement", [provisionalLabel]) - CLAUDE.md §4 rule 7 demands the disclosure live in words
 * somewhere on the pane, never demands it be repeated as a glyph too, and a glyph with no
 * accompanying words would have been the violation, not a glyph that duplicates words already
 * present. [com.kevin.legion.ui.common.DeckMarkerType] itself is untouched - other panels still
 * mark with it; only this chart's two construction sites stopped passing it.
 *
 * `internal`, not `private`, so a plain JUnit test can pin the sum invariant without Compose.
 */
internal fun categorySpendBars(budget: BudgetVsActual, maxBars: Int = 6): List<DeckBar> {
    val spent = budget.lines.filter { it.gap.actual > 0L }.sortedByDescending { it.gap.actual }
    if (spent.isEmpty()) return emptyList()

    fun bar(line: BudgetLine) = DeckBar(
        label = line.category,
        value = line.gap.actual.toFloat(),
        targetValue = line.gap.target.takeIf { it > 0L }?.toFloat(),
        valueLabel = deckWholeDollarLabel(line.gap.actual),
        mark = null,
    )

    if (spent.size <= maxBars) return spent.map { bar(it) }

    val named = spent.take(maxBars - 1)
    val folded = spent.drop(maxBars - 1)
    val foldedTotal = folded.sumOf { it.gap.actual }
    return named.map { bar(it) } + DeckBar(
        label = "OTHER ${folded.size}",
        value = foldedTotal.toFloat(),
        valueLabel = deckWholeDollarLabel(foldedTotal),
        mark = null,
    )
}

/**
 * [categorySpendBars] drawn, plus [DeckBarLabelRow] - extracted (2026-08-16) into the shared chart
 * kit rather than hand-rolled here, since [SpendTrendDrilldown]'s month chart needed the identical
 * label row and a second hand-rolled copy is exactly the drift this repo's conventions exist to
 * avoid. See [DeckBarLabelRow]'s own doc for the equal-weight-cell/ellipsis/gap-slot-alignment
 * contract this call inherits unchanged.
 *
 * Renders nothing at all when [bars] is empty - a month with no categorised spend has no chart to
 * draw, and the pane's own words say so instead.
 */
@Composable
internal fun CategorySpendChart(bars: List<DeckBar>, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        // 140dp, not the kit's 180dp drilldown default: measured on device (360x806dp, 2026-08-15),
        // the taller chart pushed the category labels and the uncategorised sentence under the
        // mic bar, so the figure and what it is made of could not be read in one glance.
        DeckBarChart(bars = bars, height = 140.dp)
        DeckBarLabelRow(bars)
    }
}

/** D10's colour: green-equivalent (credit) when there's still room, debit when over. Only used when [com.kevin.legion.plan.TrustTier] is PROVEN - a REPORTED gap always reads `sem.estimated` regardless of sign, per [BudgetLineRow]. */
private fun colorForGap(gapCents: Long, sem: com.kevin.legion.ui.theme.LegionSemantics): Color =
    if (gapCents >= 0) sem.credit else sem.debit

/**
 * The wording underneath a [BudgetLineRow] - D12's provisional-bank-data label and ticket 07's
 * unconfirmed-guess label are DIFFERENT claims (see [BudgetLine]'s doc comment), so both can be
 * true at once and both are said.
 */
private fun provisionalLabel(hasProvisionalRows: Boolean, hasPendingCategoryGuesses: Boolean): String? = when {
    hasProvisionalRows && hasPendingCategoryGuesses ->
        "includes pending transactions not yet on a statement, and a category LEGION guessed, not yet confirmed"
    hasProvisionalRows -> "includes pending transactions not yet on a statement"
    hasPendingCategoryGuesses -> "includes a category LEGION guessed, not yet confirmed"
    else -> null
}

private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

/**
 * `internal`, not `private` (mission-control ticket 16's CRED rebuild) - the SPEND hero pane on the
 * CRED root ([com.kevin.legion.ui.LedgerScreen]'s `SpendPane`) needs the IDENTICAL "August 2026"
 * wording this section's own month nav already uses, so the two never drift into two different
 * formats for the one picked month.
 */
internal fun monthLabel(month: YearMonth): String = month.format(MONTH_LABEL)

/** [YearMonth]'s own UTC start, matching every parser's `atStartOfDay(ZoneOffset.UTC)` convention. */
private fun monthStartMs(month: YearMonth): Long = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The last millisecond actually inside [month], UTC - one before the next month's own start. */
private fun monthEndMs(month: YearMonth): Long =
    month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

/**
 * D13's "a plain sentence when coverage is incomplete: which account, and which days are
 * missing." Only called when [BudgetVsActual.isComplete] is already false, so this never has to
 * describe a complete month.
 *
 * `internal`, not `private` - [com.kevin.legion.ui.TodayScreen]'s THIS MONTH section renders the
 * same [BudgetVsActual] and must say the identical sentence when coverage is incomplete (D13:
 * "state it once so four domains cannot diverge" applied to two SCREENS rendering the one figure,
 * not just four domains rendering four figures) rather than growing a second, driftable copy.
 */
internal fun coverageSentence(budget: BudgetVsActual): String {
    if (budget.coverage.isEmpty()) {
        return "No account in this entity has a statement covering ${monthLabel(budget.month)} yet - " +
            "these figures may be missing spending entirely."
    }
    val monthStart = monthStartMs(budget.month)
    val monthEnd = monthEndMs(budget.month)
    val gaps = budget.coverage.filter { !it.coversWholeMonth }.map { coverage ->
        val from = coverage.coveredFromMs
        val to = coverage.coveredToMs
        val missingStart = from != null && from > monthStart
        val missingEnd = to != null && to < monthEnd
        val description = when {
            missingStart && missingEnd -> "only covers ${compactUtcDate(from!!)}-${compactUtcDate(to!!)}"
            missingStart -> "missing before ${compactUtcDate(from!!)}"
            missingEnd -> "missing after ${compactUtcDate(to!!)}"
            else -> "only partly covered"
        }
        "${maskedAccountLabel(coverage.accountId)} ($description)"
    }
    return "Incomplete for ${monthLabel(budget.month)}: ${gaps.joinToString("; ")}. Treat this as partial."
}

/**
 * The own-account-movements drill-down (Kevin, 2026-08-13) - tapping the disclosure row
 * [BudgetSection] renders when [ExcludedOwnAccountMovements.isEmpty] is false lands here, so the
 * caveat that many dollars were pulled out of spend is inspectable, not just asserted (CLAUDE.md §4
 * rule 7's "make it inspectable" applied to this exclusion). Deliberately NOT
 * [CategoryDrilldownScreen] reused wholesale: these rows must never grow a MOVE/recategorise
 * affordance - CLAUDE.md §4 rule 5 of this ticket's own instruction is explicit that the guesser
 * gate is unchanged, and a person is not a merchant here any more than in [isBankNoiseKey] - so this
 * is a read-only list, on purpose.
 */
@Composable
fun ExcludedOwnAccountMovementsScreen(
    entity: LedgerEntity,
    excluded: ExcludedOwnAccountMovements,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("< BACK", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "Excluded from spend",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Text(
                com.kevin.legion.ledger.excludedOwnAccountMovementsSentence(excluded, entity.currency),
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Hairline()
            if (excluded.rows.isEmpty()) {
                Text(
                    "Nothing excluded this month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(excluded.rows, key = { it.id }) { txn ->
                        ExcludedOwnAccountMovementRow(txn)
                        Hairline()
                    }
                }
            }
        }
    }
}

/** One read-only row: date, raw description, amount - no MOVE affordance, see [ExcludedOwnAccountMovementsScreen]'s own doc comment for why. */
@Composable
private fun ExcludedOwnAccountMovementRow(txn: LedgerTransaction) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(displayDescription(txn.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(documentDateCompact(txn.txnDate), style = LegionType.stamp, color = sem.faint)
            Text(
                formatMoney(txn.amountCents, txn.currency),
                style = LegionType.amount,
                color = sem.debit,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

private val COMPACT_UTC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

private fun compactUtcDate(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().format(COMPACT_UTC_DATE)

// ------------------------------------------------------------------------ previews
//
// Provisional, same status the old ProfitAndLossSection's previews carried: written and intended
// to be checked in Android Studio's preview renderer, but that render could not be performed from
// this session (no Studio/emulator surface reachable from the coding-agent sandbox) - see the
// build report for this ticket. Named, surfaced gap, not a silent skip.

private val previewMonth = YearMonth.of(2026, 7)

private fun previewCoverage() = listOf(AccountCoverage("BOFA ****4471", true, monthStartMs(previewMonth), monthEndMs(previewMonth)))

// ticket 10 (quant-viz): shared fixtures for the two new hero graphics, reused across every
// "budget loaded" preview below - a gap month (June) plus a real July figure so the sparkline
// preview exercises both branches of monthlySpendBars' own hole-mapping.
private val previewSpendTrend = listOf(
    MonthSpend(YearMonth.of(2026, 5), totalCents = 412_00L, isComplete = true, hasProvisionalRows = false),
    MonthSpend(previewMonth, totalCents = 388_50L, isComplete = true, hasProvisionalRows = false),
)
private fun previewDailyTransactions() = listOf(
    LedgerTransaction(
        id = 901, sourceFile = "eStmt.pdf", accountId = "BOFA ****4471", currency = LedgerCurrency.USD,
        txnDate = monthStartMs(previewMonth), description = "TRADER JOES", amountCents = -4_120,
        lineRef = "1", ingestMethod = IngestMethod.DETERMINISTIC,
    ),
    LedgerTransaction(
        id = 902, sourceFile = "eStmt.pdf", accountId = "BOFA ****4471", currency = LedgerCurrency.USD,
        txnDate = monthStartMs(previewMonth) + 14L * 24 * 60 * 60 * 1000, description = "AMAZON", amountCents = -9_999,
        lineRef = "2", ingestMethod = IngestMethod.DETERMINISTIC,
    ),
)

@Preview(name = "Budget: two categories, under and over", widthDp = 360)
@Composable
private fun PreviewBudgetBasic() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = listOf(
                    BudgetLine(
                        category = "Groceries",
                        gap = com.kevin.legion.plan.PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                    BudgetLine(
                        category = "Dining Out",
                        gap = com.kevin.legion.plan.PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                    // ticket 02 (quant-viz): a zero-target line - spend with no budget set (D10's
                    // "$0 budgeted, $42 spent" case) - draws NO meter, only the two text lines.
                    BudgetLine(
                        category = "Shopping",
                        gap = com.kevin.legion.plan.PlanGap(target = 0L, actual = 4_200L, gap = -4_200L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                ),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = previewCoverage(),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            canGoPrevMonth = true, canGoNextMonth = false,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: own-account movements excluded, disclosed in words", widthDp = 360)
@Composable
private fun PreviewBudgetExcludedOwnAccountMovements() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = listOf(
                    BudgetLine(
                        category = "Groceries",
                        gap = com.kevin.legion.plan.PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                ),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = previewCoverage(),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(
                    count = 3,
                    totalCents = 34_011L + 34_907L + 13_955L,
                    rows = listOf(
                        LedgerTransaction(
                            id = 101, sourceFile = "eStmt.pdf", accountId = "4111111115042",
                            currency = LedgerCurrency.USD, txnDate = System.currentTimeMillis(),
                            description = "Online Banking payment to CRD 7823 Confirmation# 0649409616",
                            amountCents = -34_011L, lineRef = "1", ingestMethod = IngestMethod.DETERMINISTIC,
                        ),
                    ),
                ),
            ),
            canGoPrevMonth = true, canGoNextMonth = true,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: uncategorised bucket loud even at a small amount", widthDp = 360)
@Composable
private fun PreviewBudgetUncategorized() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = listOf(
                    BudgetLine(
                        category = "Groceries",
                        gap = com.kevin.legion.plan.PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = TrustTier.PROVEN),
                        hasProvisionalRows = false,
                        hasPendingCategoryGuesses = false,
                    ),
                ),
                uncategorized = UncategorizedSpend(spentCents = 3_412L, hasProvisionalRows = false),
                coverage = previewCoverage(),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            canGoPrevMonth = true, canGoNextMonth = true,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: pending bank data and an unconfirmed AI guess", widthDp = 360)
@Composable
private fun PreviewBudgetReported() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = listOf(
                    BudgetLine(
                        category = "Shopping",
                        gap = com.kevin.legion.plan.PlanGap(target = 30_000L, actual = 12_000L, gap = 18_000L, tier = TrustTier.REPORTED),
                        hasProvisionalRows = true,
                        hasPendingCategoryGuesses = true,
                    ),
                ),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = previewCoverage(),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            canGoPrevMonth = true, canGoNextMonth = true,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: incomplete coverage, one account missing its tail", widthDp = 360)
@Composable
private fun PreviewBudgetIncompleteCoverage() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = emptyList(),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = listOf(
                    AccountCoverage("BOFA ****4471", coversWholeMonth = false, coveredFromMs = monthStartMs(previewMonth), coveredToMs = monthStartMs(previewMonth) + 20L * 24 * 60 * 60 * 1000),
                ),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            canGoPrevMonth = true, canGoNextMonth = true,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: no coverage at all - the empty-list-is-not-complete case", widthDp = 360)
@Composable
private fun PreviewBudgetNoCoverage() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = emptyList(),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = emptyList(),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            canGoPrevMonth = false, canGoNextMonth = true,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: no spending this month", widthDp = 360)
@Composable
private fun PreviewBudgetEmptyMonth() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth,
            spendTrend = previewSpendTrend,
            dailyTransactions = previewDailyTransactions(),
            budget = BudgetVsActual(
                entity = LedgerEntity.US,
                month = previewMonth,
                lines = emptyList(),
                uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                coverage = previewCoverage(),
                excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
            ),
            canGoPrevMonth = true, canGoNextMonth = true,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Budget: loading", widthDp = 360)
@Composable
private fun PreviewBudgetLoading() = LegionTheme {
    Surface {
        BudgetSection(
            month = previewMonth, budget = null,
            spendTrend = null, dailyTransactions = emptyList(),
            canGoPrevMonth = false, canGoNextMonth = false,
            onPrevMonth = {}, onNextMonth = {}, onOpenCategory = {}, onOpenExcludedOwnAccountMovements = {}, onOpenTrend = {},
        )
    }
}

@Preview(name = "Excluded own-account movements: two rows", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewExcludedOwnAccountMovements() = LegionTheme {
    ExcludedOwnAccountMovementsScreen(
        entity = LedgerEntity.US,
        excluded = ExcludedOwnAccountMovements(
            count = 2,
            totalCents = 34_011L + 34_907L,
            rows = listOf(
                LedgerTransaction(
                    id = 1, sourceFile = "eStmt.pdf", accountId = "4111111115042",
                    currency = LedgerCurrency.USD, txnDate = System.currentTimeMillis(),
                    description = "Online Banking payment to CRD 7823 Confirmation# 0649409616",
                    amountCents = -34_011L, lineRef = "1", ingestMethod = IngestMethod.DETERMINISTIC,
                ),
                LedgerTransaction(
                    id = 2, sourceFile = "eStmt.pdf", accountId = "4111111115042",
                    currency = LedgerCurrency.USD, txnDate = System.currentTimeMillis(),
                    description = "Mobile Banking payment to CRD 7823 Confirmation# 14lmchyt7",
                    amountCents = -34_907L, lineRef = "2", ingestMethod = IngestMethod.DETERMINISTIC,
                ),
            ),
        ),
        onBack = {},
    )
}

@Preview(name = "Excluded own-account movements: empty", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewExcludedOwnAccountMovementsEmpty() = LegionTheme {
    ExcludedOwnAccountMovementsScreen(
        entity = LedgerEntity.US,
        excluded = ExcludedOwnAccountMovements(0, 0L, emptyList()),
        onBack = {},
    )
}

// The SPEND hero chart (Kevin, 2026-08-15). Two previews on purpose: the ordinary case, and the
// folded case, since `OTHER n` only appears past the cap and is the branch most likely to look
// wrong at phone width. Same provisional status as every preview above - written to be rendered in
// Studio, not rendered from this session.

@Preview(name = "Spend hero: four categories, biggest labelled", widthDp = 360)
@Composable
private fun PreviewCategorySpendChart() = LegionTheme {
    Surface {
        CategorySpendChart(
            categorySpendBars(
                BudgetVsActual(
                    entity = LedgerEntity.US,
                    month = previewMonth,
                    lines = listOf(
                        BudgetLine("Groceries", com.kevin.legion.plan.PlanGap(60_000L, 41_200L, 18_800L, TrustTier.PROVEN), false, false),
                        BudgetLine("Dining Out", com.kevin.legion.plan.PlanGap(20_000L, 24_500L, -4_500L, TrustTier.PROVEN), false, false),
                        BudgetLine("Shopping", com.kevin.legion.plan.PlanGap(0L, 12_000L, -12_000L, TrustTier.REPORTED), true, false),
                        BudgetLine("Fuel", com.kevin.legion.plan.PlanGap(15_000L, 8_400L, 6_600L, TrustTier.PROVEN), false, false),
                    ),
                    uncategorized = UncategorizedSpend(spentCents = 3_412L, hasProvisionalRows = false),
                    coverage = previewCoverage(),
                    excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
                ),
            ),
        )
    }
}

@Preview(name = "Spend hero: nine categories, the tail folded into OTHER", widthDp = 360)
@Composable
private fun PreviewCategorySpendChartFolded() = LegionTheme {
    Surface {
        CategorySpendChart(
            categorySpendBars(
                BudgetVsActual(
                    entity = LedgerEntity.US,
                    month = previewMonth,
                    lines = (1..9).map { i ->
                        BudgetLine(
                            "Category $i",
                            com.kevin.legion.plan.PlanGap(0L, i * 5_000L, -(i * 5_000L), TrustTier.PROVEN),
                            hasProvisionalRows = false,
                            hasPendingCategoryGuesses = false,
                        )
                    },
                    uncategorized = UncategorizedSpend(spentCents = 0L, hasProvisionalRows = false),
                    coverage = previewCoverage(),
                    excludedOwnAccountMovements = ExcludedOwnAccountMovements(0, 0L, emptyList()),
                ),
            ),
        )
    }
}

@Preview(name = "Add category: empty, no error", widthDp = 360)
@Composable
private fun PreviewAddCategoryEmpty() = LegionTheme {
    Surface { AddCategoryRow(errorText = null, successNonce = 0, onAdd = {}) }
}

@Preview(name = "Add category: rejected, reason shown in words", widthDp = 360)
@Composable
private fun PreviewAddCategoryRejected() = LegionTheme {
    Surface { AddCategoryRow(errorText = "\"Pets\" already exists.", successNonce = 0, onAdd = {}) }
}
