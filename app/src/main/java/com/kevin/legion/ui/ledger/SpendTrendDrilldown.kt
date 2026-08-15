package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.MonthSpend
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ticket 04 (quant-viz): month-over-month spend, opened by tapping the month label in
 * [BudgetSection]'s header row (the `< AUGUST 2026 >` line) - same internal-Compose-state pattern
 * [CategoryDrilldownScreen] uses, no nav-graph route (`LegionRoute` deliberately carries no
 * argument routes, per that screen's own doc comment).
 *
 * [trend] is `null` while [com.kevin.legion.ledger.LedgerController.monthlySpendTrend] is still
 * loading - the caller ([com.kevin.legion.ui.LedgerScreen]) owns that fetch, this composable is
 * display-only, matching every other ledger drilldown's "no controller reference inside the
 * content composable" split.
 *
 * **The gap-vs-zero rule, read onto months.** [trend] omits any month
 * [com.kevin.legion.ledger.LedgerController.monthlySpendTrend] itself omitted (no coverage, nothing
 * spent - see that function's own doc comment) - [monthlySpendBars] is what turns a HOLE in
 * [trend]'s own month sequence into a `null` [DeckBar] slot for the chart, rather than this
 * composable inferring it from anything else.
 */
@Composable
fun SpendTrendDrilldown(
    entity: LedgerEntity,
    trend: List<MonthSpend>?,
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
            SectionHeader("SPEND BY MONTH", entity.displayName)
            when {
                trend == null -> Text(
                    "Loading...", style = LegionType.stamp, color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                trend.isEmpty() -> Text(
                    "No months with spend or coverage yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
                else -> {
                    DeckBarChart(bars = monthlySpendBars(trend))
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(trend.sortedByDescending { it.month }, key = { it.month.toString() }) { spend ->
                            SpendTrendRow(spend, entity)
                        }
                    }
                }
            }
        }
    }
}

/**
 * One month's row below the chart - newest first (the caller sorts before calling this). The
 * value string itself carries `"(incomplete)"` when [MonthSpend.isComplete] is false (ticket 04:
 * "append the word INSIDE the value string"), and the two caveat lines beneath are words, never
 * colour alone (CLAUDE.md §4 rule 5), matching [coverageSentence]/[provisionalLabel]'s own posture
 * elsewhere on this screen.
 */
@Composable
private fun SpendTrendRow(spend: MonthSpend, entity: LedgerEntity) {
    val sem = LocalLegionSemantics.current
    val valueText = formatMoney(spend.totalCents, entity.currency) + if (!spend.isComplete) " (incomplete)" else ""
    Column(Modifier.fillMaxWidth()) {
        DeckRow(label = monthTrendLabel(spend.month), value = valueText)
        if (!spend.isComplete) {
            // ADVISORY (ticket 13 re-home): an incomplete-coverage caveat, the same shape as
            // UNRECONCILED - act on this, not a failed gate.
            Text(
                "not every account covered - total may be low",
                style = LegionType.stamp,
                color = sem.estimated,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        if (spend.hasProvisionalRows) {
            Text(
                "includes pending rows",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * [trend] (ascending by construction - [com.kevin.legion.ledger.LedgerController.monthlySpendTrend]
 * walks forward from its own earliest month) folded into one [DeckBar] per calendar month spanning
 * `trend.first().month..trend.last().month` - a month PRESENT in [trend] draws its real
 * [MonthSpend.totalCents]; a month ABSENT (a hole [monthlySpendTrend] itself left, per that
 * function's own gap-vs-zero doc comment) draws as `null`, the kit's own gap slot. `internal`, not
 * `private`, so this is plain-JUnit-testable without Compose or Robolectric.
 *
 * [DeckBar.valueLabel] is set ONLY on the LATEST month in [trend] (ticket 04: no per-bar labels
 * otherwise) - the latest ELEMENT of [trend], not necessarily the latest calendar month in the
 * reconstructed span, since [trend] can legitimately end before "now" if the current month itself
 * has no coverage yet.
 */
internal fun monthlySpendBars(trend: List<MonthSpend>): List<DeckBar?> {
    if (trend.isEmpty()) return emptyList()
    val byMonth = trend.associateBy { it.month }
    val start = trend.first().month
    val end = trend.last().month
    val latestMonth = trend.last().month
    val months = generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }.toList()
    return months.map { month ->
        val spend = byMonth[month] ?: return@map null
        DeckBar(
            label = monthAbbrevLabel(month),
            value = spend.totalCents.toFloat(),
            valueLabel = if (month == latestMonth) formatCents(spend.totalCents) else null,
        )
    }
}

/** `"JAN"`.."DEC"` - the chart's per-bar x-axis label. */
internal fun monthAbbrevLabel(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase()

private val MONTH_TREND_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

/** `"July 2026"` - the plain-list row's own label, matching [BudgetSection]'s own `monthLabel` formatting. */
private fun monthTrendLabel(month: YearMonth): String = month.format(MONTH_TREND_LABEL)

// ------------------------------------------------------------------------ previews

private val previewTrend = listOf(
    MonthSpend(YearMonth.of(2026, 5), totalCents = 412_00L, isComplete = true, hasProvisionalRows = false),
    // June absent on purpose - a gap month, per the file doc's invariant, renders as a hole here
    // rather than a MonthSpend(totalCents = 0).
    MonthSpend(YearMonth.of(2026, 7), totalCents = 388_50L, isComplete = false, hasProvisionalRows = true),
    MonthSpend(YearMonth.of(2026, 8), totalCents = 512_25L, isComplete = true, hasProvisionalRows = false),
)

@Preview(name = "Spend trend: three months, one gap, one incomplete", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewSpendTrendDrilldown() = LegionTheme {
    SpendTrendDrilldown(entity = LedgerEntity.US, trend = previewTrend, onBack = {})
}

@Preview(name = "Spend trend: loading", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewSpendTrendDrilldownLoading() = LegionTheme {
    SpendTrendDrilldown(entity = LedgerEntity.US, trend = null, onBack = {})
}

@Preview(name = "Spend trend: no months yet", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewSpendTrendDrilldownEmpty() = LegionTheme {
    SpendTrendDrilldown(entity = LedgerEntity.US, trend = emptyList(), onBack = {})
}
