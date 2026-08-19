package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountCoverage
import com.kevin.legion.ledger.BudgetLine
import com.kevin.legion.ledger.BudgetVsActual
import com.kevin.legion.ledger.CategorySetResult
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ledger.uncategorizedExcludedSentence
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.bucketDailySumCents
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.documentDateCompact
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The category drill-down (Kevin, 2026-08-07: "I want to be able to drill down into a category and
 * see the transactions in there") - tapping a [BudgetLineRow]/the uncategorised bucket in
 * [BudgetSection] lands here instead of a navigation route, matching [com.kevin.legion.ui.NotesScreen]'s
 * "owns its own internal drill-down... rather than adding nav-graph sub-routes with arguments" pattern
 * (`LegionRoute` deliberately carries no argument routes).
 *
 * [category] `== null` is the `(uncategorised)` bucket (D11), not "every transaction" - the caller
 * ([com.kevin.legion.ui.LedgerScreen]) is responsible for loading exactly
 * [com.kevin.legion.ledger.LedgerController.categoryTransactions]'s result for the currently-picked
 * budget month and handing it in as [transactions]; this composable is display-only, same
 * "no controller reference inside the content composable" split every other ledger screen here uses.
 *
 * **Hand recategorise (Kevin, 2026-08-07: "I drilled into Shopping, saw my two Petco charges, and
 * had no way to move them")**: each row grows a MOVE affordance that opens an inline panel routed
 * through [onSetCategory] - which the caller wires straight to the SAME
 * [com.kevin.legion.ledger.LedgerController.setCategory] the voice tool `set_category` already
 * uses (no second write path). [categoryNames] is the fixed, Room-stored category list (D14) -
 * never free text. [onPreviewRecategorizeCount] lets the panel show the blast radius BEFORE
 * committing, as the driver edits the derived key.
 *
 * **Daily-spend bars (quant-viz ticket 03), a REAL category only.** [month]/[coverage] exist ONLY
 * to feed [categoryDailySpendBars] - `month` bounds the day-by-day sum, `coverage` is
 * [com.kevin.legion.ledger.BudgetVsActual.coverage] for that same month, threaded straight through
 * by the caller from the [com.kevin.legion.ledger.BudgetVsActual] it already loaded for the budget
 * section (no new DB read here - see [categoryDailySpendBars]'s own doc comment). The chart sits
 * ABOVE the transaction list, which is unchanged.
 *
 * **The uncategorised bucket draws a DIFFERENT chart (Kevin, 2026-08-18: "tapping Cred section
 * brings me to a chart. its useless. uncategorized dominates the whole thing. exclude it from
 * visuals").** `HalfTile`'s CRED tap (`ui.TodayScreen`) and the ALARM/quarantine ALERTS rows both
 * land here with `category == null` directly, skipping the CRED root entirely - so [budget], the
 * SAME [com.kevin.legion.ledger.BudgetVsActual] the caller already holds for [month], is threaded
 * in here too, and ONLY for `category == null` this screen draws
 * [com.kevin.legion.ui.ledger.categorySpendBars]/[com.kevin.legion.ui.ledger.CategorySpendChart] -
 * the SAME real-category-only breakdown the CRED root's own SPEND hero already draws (uncategorised
 * never among its bars, by construction - see that function's own doc comment) - instead of
 * [categoryDailySpendBars]'s per-day total of the uncategorised bucket alone, which is USELESS as a
 * visual precisely because every one of its bars is, by definition, the exact thing this screen
 * exists to fix: nothing has been sorted into a category yet. [uncategorizedExcludedSentence] sits
 * directly under that chart, in words (CLAUDE.md §4 rule 5) - the uncategorised total is never
 * dropped, only kept out of the PICTURE, matching Kevin's own "keep it disclosed, not hidden" call
 * on the equivalent SPEND-hero decision. A real category (`category != null`) is unaffected - its
 * own [categoryDailySpendBars] chart was never the problem (it was already scoped to that one
 * category, never "dominated" by anything).
 *
 * **SET TARGET affordance (quant-viz ticket 09, Kevin 2026-08-13: "set a budget target so i can see
 * the meters").** Rendered at the TOP of this screen, under the header, ONLY when [category] is a
 * real category (`!= null`) - the uncategorised bucket has no target by D11, same reason it has no
 * meter ([com.kevin.legion.ui.ledger.BudgetLineRow]'s own zero-target guard). [entity] resolves the
 * currency the words line and the parsed write are stated in. [currentTargetCents] is the literal
 * explicit target on record for this category as of [month] - see
 * [com.kevin.legion.ledger.LedgerController.currentTargetCents]'s own doc comment for why this is a
 * dedicated DAO read rather than [com.kevin.legion.ledger.BudgetLine.gap]'s `target`, which cannot
 * tell "never set" apart from "explicitly set to zero". [setTargetErrorText]/[setTargetSuccessNonce]
 * mirror [AddCategoryRow]'s own state-holder contract exactly - a live signal the caller
 * ([com.kevin.legion.ui.LedgerScreen]) holds OUTSIDE its async DB-load state, so a rejected parse
 * survives a recomposition instead of flashing once, and the typed text is cleared only on a
 * CONFIRMED write. [onSetTarget] hands back the raw typed dollars text - parsing
 * ([parseDollarsToCents]) and the [com.kevin.legion.ledger.LedgerController.setBudget] write both
 * happen in the caller, same "content composable owns no controller reference" split every other
 * write on this screen (recategorise, add-category) already follows.
 */
@Composable
fun CategoryDrilldownScreen(
    category: String?,
    entity: LedgerEntity,
    transactions: List<LedgerTransaction>,
    loading: Boolean,
    categoryNames: List<String>,
    month: YearMonth,
    coverage: List<AccountCoverage>,
    // 2026-08-18 fix: only ever read for the `category == null` branch's own chart - see this
    // composable's own doc comment. `null` is a safe, defensive "no chart yet" (the real category
    // path never looks at this field), matching the same "budget loading" window `pnlMonth`'s
    // caller already has to handle for the BUDGET drilldown.
    budget: BudgetVsActual?,
    currentTargetCents: Long?,
    setTargetErrorText: String?,
    setTargetSuccessNonce: Int,
    onSetTarget: (String) -> Unit,
    onPreviewRecategorizeCount: suspend (String) -> Int,
    onSetCategory: suspend (merchantKey: String, category: String) -> CategorySetResult,
    // 2026-08-18: only the `category == null` branch's chart uses this - a bar on the
    // uncategorised bucket's breakdown opens that real category. Defaulted so the real-category
    // path, which draws its own per-day chart and has nothing to drill into, needs no call-site
    // change.
    onOpenCategory: (String) -> Unit = {},
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    // Which row's MOVE panel is open, at most one at a time - `null` means none. Keyed by
    // transaction id rather than a boolean-per-row so opening one row's panel implicitly closes
    // any other, matching the single-focus editing pattern the rest of this screen uses.
    var expandedTxnId by remember(category) { mutableStateOf<Long?>(null) }

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
                categoryDrilldownTitle(category),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (category == null) {
                Text(
                    "not assigned to any category",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            } else {
                // ticket 09: never rendered for the uncategorised bucket - see this composable's own
                // doc comment for why (D11, same reason it grows no meter).
                SetTargetRow(
                    currentTargetCents = currentTargetCents,
                    entity = entity,
                    month = month,
                    errorText = setTargetErrorText,
                    successNonce = setTargetSuccessNonce,
                    onSet = onSetTarget,
                )
            }
            if (category == null) {
                // 2026-08-18 fix: the real-category breakdown, uncategorised excluded from the
                // PICTURE by construction (categorySpendBars never puts it among its own bars) -
                // see this composable's own doc comment for why the per-day bucket-only chart this
                // replaces was useless here. Renders nothing when `budget` hasn't loaded yet or has
                // no categorised spend this month - CategorySpendChart's own "empty bars draws
                // nothing" behaviour, not a special case added here.
                val chartBudget = budget
                if (chartBudget != null) {
                    // Tappable here too (2026-08-18): this branch draws the real per-category
                    // breakdown, so a bar means the same thing it means on the SPEND pane. Landing
                    // on the uncategorised bucket and tapping "Groceries" should open Groceries.
                    // The folded OTHER bar carries no single category and is inert here rather
                    // than opening a screen this one cannot reach - see categorySpendChartData.
                    val chartData = categorySpendChartData(chartBudget)
                    CategorySpendChart(
                        chartData.bars,
                        modifier = Modifier.padding(top = 4.dp),
                        onBarTap = { index ->
                            chartData.categories.getOrNull(index)?.let(onOpenCategory)
                        },
                    )
                    Text(
                        uncategorizedExcludedSentence(chartBudget.uncategorized, entity.currency),
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp, top = 4.dp),
                    )
                }
            } else {
                // Rendered unconditionally, even while `loading`/`transactions` is empty - ticket 03's
                // "render the chart anyway - the kit handles it; do not conditionally hide" - the kit
                // draws a baseline of gap underlines for an all-null/all-zero series rather than nothing.
                val dailyBars = categoryDailySpendBars(transactions, month, coverage)
                DeckBarChart(
                    bars = dailyBars,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Kevin, 2026-08-18: "the x axis has no title i have no idea what that shows. its
                // probably the days but i cant tell can i". He was right about what it was and
                // right that nothing on screen said so - the bars have carried a day-of-month
                // label since they were written, and this screen simply never drew the label row
                // (the SPEND pane's own chart does, via CategorySpendChart).
                DayOfMonthAxis(dailyBars)
                Text(
                    "one bar per day of ${monthLabel(month)} - days no statement covers are marked, not zero",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }
            Hairline()
            when {
                loading -> Text(
                    "Loading...", style = LegionType.stamp, color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                transactions.isEmpty() -> Text(
                    "No transactions here this month.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(transactions, key = { it.id }) { txn ->
                        CategoryDrilldownRow(
                            txn = txn,
                            expanded = expandedTxnId == txn.id,
                            onToggleExpanded = {
                                expandedTxnId = if (expandedTxnId == txn.id) null else txn.id
                            },
                            categoryNames = categoryNames,
                            onPreviewRecategorizeCount = onPreviewRecategorizeCount,
                            onSetCategory = onSetCategory,
                            onDone = { expandedTxnId = null },
                        )
                        Hairline()
                    }
                }
            }
        }
    }
}

/** `"(uncategorised)"` for the null bucket (D11's own wording), the category name otherwise. */
internal fun categoryDrilldownTitle(category: String?): String = category ?: "(uncategorised)"

/**
 * The SET TARGET affordance (quant-viz ticket 09) - mirrors [AddCategoryRow]'s exact shape: local
 * text-field state only, [errorText] a live signal from the caller's state holder so a rejected
 * parse survives a recomposition instead of flashing once, and [successNonce] bumping ONLY on a
 * CONFIRMED write (the [LaunchedEffect] below clears the typed text then, same as [AddCategoryRow]'s
 * own). [onSet] hands the RAW typed text back to the caller - this composable does no parsing and no
 * write of its own, matching every other affordance on this screen (recategorise, add-category)
 * holding no controller reference.
 */
@Composable
internal fun SetTargetRow(
    currentTargetCents: Long?,
    entity: LedgerEntity,
    month: YearMonth,
    errorText: String?,
    successNonce: Int,
    onSet: (String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    var text by remember { mutableStateOf("") }

    LaunchedEffect(successNonce) { text = "" }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(currentTargetSentence(currentTargetCents, entity, month), style = LegionType.stamp, color = sem.faint)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Set target (dollars)", style = LegionType.stamp) },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { if (text.isNotBlank()) onSet(text) }) {
                Text("SET", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (errorText != null) {
            // ADVISORY (ticket 13 re-home): a form validation error, not a failed gate.
            Text(errorText, style = LegionType.stamp, color = sem.estimated, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * The words next to [SetTargetRow]'s field - states what the affordance is currently set to change,
 * per its own doc comment ("the affordance states what it is changing"). `null` [targetCents] is a
 * category with NO [com.kevin.legion.data.local.BudgetTarget] row ever written (see
 * [com.kevin.legion.ledger.LedgerController.currentTargetCents]'s doc comment); `0L` is an EXPLICIT
 * zero write, worded differently on purpose so the driver can tell "never touched this" apart from
 * "deliberately silenced the meter". `internal`, not `private`, for the same plain-JUnit-testability
 * reason every other pure sentence function on this screen uses.
 */
internal fun currentTargetSentence(targetCents: Long?, entity: LedgerEntity, month: YearMonth): String = when (targetCents) {
    null -> "no target set"
    0L -> "target ${formatMoney(0L, entity.currency)} - no meter is drawn at zero"
    else -> "target ${formatMoney(targetCents, entity.currency)} since ${categoryDrilldownMonthLabel(month)}"
}

/** The single rejection message [SetTargetRow] shows when [parseDollarsToCents] returns `null` - one wording, not re-typed at each call site. */
internal fun dollarsParseErrorMessage(): String = "Enter a dollar amount, like 300 or 299.99"

/**
 * Dollars-text -> cents, WITHOUT `Double` (CLAUDE.md §4 rule 3 - money is `Long` cents, and a parse
 * on the way to a stored target is no exception even though the driver types dollars). Splits on
 * `.`: the dollars part and a 0-2 digit cents part are each parsed with `toLongOrNull()`, and a
 * one-digit cents part is padded (`"5"` -> `50`, i.e. fifty cents, not five) before being added to
 * `dollars * 100`.
 *
 * Rejects, returning `null`: blank/whitespace-only text, a leading `-` (no negative target), more
 * than one `.`, a cents part longer than two digits (more than 2 decimal places), anything that
 * fails `toLongOrNull()` (non-numeric), and a result over `9_999_999_99` cents ($9,999,999.99).
 *
 * `internal`, not `private`, so [LedgerSetTargetParserTest] can pin every one of the ticket's own
 * exact-Long test cases without Robolectric or a Composable host.
 */
internal fun parseDollarsToCents(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isBlank() || trimmed.startsWith("-")) return null
    val parts = trimmed.split(".")
    if (parts.size > 2) return null
    val dollarsText = parts[0]
    val centsText = if (parts.size == 2) parts[1] else "0"
    if (centsText.length > 2) return null
    val dollars = dollarsText.toLongOrNull() ?: return null
    if (dollars < 0) return null
    val centsPart = centsText.padEnd(2, '0').toLongOrNull() ?: return null
    if (centsPart < 0) return null
    val totalCents = dollars * 100 + centsPart
    if (totalCents > 9_999_999_99L) return null
    return totalCents
}

/** `MMMM yyyy`, the same shape [com.kevin.legion.ui.ledger.BudgetSection]'s own private `monthLabel` formats with - a private copy here per this file's own established duplication convention (see [monthStartMs]'s doc comment right below). */
private val CATEGORY_DRILLDOWN_MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun categoryDrilldownMonthLabel(month: YearMonth): String = month.format(CATEGORY_DRILLDOWN_MONTH_LABEL)

/** [YearMonth]'s own UTC start, matching every parser's `atStartOfDay(ZoneOffset.UTC)` convention - a private copy of [com.kevin.legion.ledger.LedgerController]'s own (private, unreachable from here) `monthStartMillis`, same duplication [com.kevin.legion.ui.ledger.BudgetSection]'s own copy already carries. */
private fun monthStartMs(month: YearMonth): Long = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The last millisecond actually inside [month], UTC - one before the next month's own start. */
private fun monthEndMs(month: YearMonth): Long =
    month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1

/**
 * Ticket 03's pure mapping: [transactions] (already [com.kevin.legion.ledger.LedgerController.categoryTransactions]'s
 * result - the SAME operating-expense rows the drilldown list below renders, no second query - see
 * [CategoryDrilldownScreen]'s own doc comment) folded into one [DeckBar] per day of [month].
 *
 * `internal`, not `private`, so a plain JUnit test can pin "the chart's summed cents equal the
 * drilldown's own total" (ticket 03's own verification step) without Robolectric or a Canvas.
 *
 * [transactions] are expense rows by construction ([com.kevin.legion.ledger.operatingExpenses]
 * filters to `amountCents < 0` before [com.kevin.legion.ledger.LedgerController.categoryTransactions]
 * ever sees them), so `abs(amountCents)` is the positive spend figure [bucketDailySumCents] wants -
 * never a second sign convention invented here.
 *
 * [coverage] becomes [bucketDailySumCents]'s `coveredRanges`: only accounts with BOTH bounds known
 * contribute a range (an account with a null bound never had an ingested file cover this month at
 * all, per [AccountCoverage]'s own doc comment, so it has nothing to assert) - this is also what
 * makes "days in the future of the CURRENT month pass null" fall out for free rather than needing
 * its own branch: a statement can only ever cover days already lived, so [coverage]'s ranges never
 * reach into the future, and a future day with no real sample lands in [bucketDailySumCents]'s
 * "outside every covered range" branch exactly like any other uncovered day.
 *
 * The single max-spend day gets [DeckBar.valueLabel] (ticket 03: "selective labels per the kit's
 * doc"); every other day's label stays `null`. A day with a real row that happens to average $0.00
 * (a full refund landing the same day as a purchase) never wins the max, so this never mislabels a
 * quiet day as the interesting one.
 */
/**
 * The day-of-month scale under [categoryDailySpendBars]'s chart.
 *
 * **Sparse on purpose.** [com.kevin.legion.ui.common.DeckBarLabelRow] gives every bar an equal
 * share and prints all of them, which is right for six category bars and unreadable for
 * thirty-one days - at 384dp that is roughly 12dp per label, so a full row would render as a
 * smear of clipped digits and be no more legible than the nothing it replaced. Anchors only: the
 * 1st, 8th, 15th, 22nd and the month's own last day, whatever that is.
 *
 * Alignment comes from the same rule the label row uses - one `weight(1f)` cell per bar inside the
 * same 12dp horizontal padding the chart draws in - so a printed number sits over its own day
 * rather than near it. Blank cells hold their place so the anchors cannot slide.
 */
@Composable
private fun DayOfMonthAxis(bars: List<DeckBar?>, modifier: Modifier = Modifier) {
    if (bars.isEmpty()) return
    val sem = LocalLegionSemantics.current
    // Index i is day i+1 - categoryDailySpendBars emits one slot per day from the 1st, including
    // the days it has no coverage for (those are null bars, not missing slots), so the position
    // is trustworthy even where the bar itself is absent.
    val anchors = setOf(0, 7, 14, 21, bars.lastIndex)
    Row(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        bars.indices.forEach { i ->
            Text(
                if (i in anchors) (i + 1).toString() else "",
                style = LegionType.stamp,
                color = sem.faint,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal fun categoryDailySpendBars(
    transactions: List<LedgerTransaction>,
    month: YearMonth,
    coverage: List<AccountCoverage>,
): List<DeckBar?> {
    val samples = transactions.map { it.txnDate to kotlin.math.abs(it.amountCents) }
    val coveredRanges = coverage.mapNotNull { c ->
        val from = c.coveredFromMs
        val to = c.coveredToMs
        if (from != null && to != null) from..to else null
    }
    // UTC throughout, matching monthStartMs/monthEndMs above and every parser's own
    // atStartOfDay(ZoneOffset.UTC) stamping of txnDate - the device's LOCAL zone (the kit's usual
    // default) would shift a transaction dated the 1st at UTC midnight onto the last day of the
    // PREVIOUS month for anyone west of Greenwich, which is exactly the "dates a day early" bug
    // MEMORY.md already records once for a mismatched convention.
    val monthStart = monthStartMs(month)
    val monthEnd = monthEndMs(month)
    val dailyCents = bucketDailySumCents(samples, monthStart, monthEnd, coveredRanges, zone = ZoneOffset.UTC)
    val dayStarts = dailyBuckets(monthStart, monthEnd, zone = ZoneOffset.UTC)
    val maxIndex = dailyCents.withIndex()
        .filter { (_, cents) -> cents != null }
        .maxByOrNull { (_, cents) -> cents!! }
        ?.index
    return dailyCents.mapIndexed { i, cents ->
        if (cents == null) {
            null
        } else {
            DeckBar(
                label = Instant.ofEpochMilli(dayStarts[i]).atZone(ZoneOffset.UTC).toLocalDate().dayOfMonth.toString(),
                value = cents.toFloat(),
                valueLabel = if (i == maxIndex) formatCents(cents) else null,
            )
        }
    }
}

/**
 * One row: date, description, amount WITH its currency ([formatMoney], never bare `formatCents` -
 * a category can only ever hold one entity's own currency by construction, but the figure still
 * carries it per the same audit rule [AccountBalanceRow] follows), and - in words, never colour
 * alone (CLAUDE.md §4 rule 7) - [LedgerCategoryResolver.rowNote] whenever this row is unverified, a
 * voice-logged pending entry, or still carrying an unconfirmed AI category guess. A MOVE affordance
 * opens [RecategorizePanel] beneath it, [expanded] hoisted by the caller so only one row's panel is
 * ever open at once.
 */
@Composable
private fun CategoryDrilldownRow(
    txn: LedgerTransaction,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    categoryNames: List<String>,
    onPreviewRecategorizeCount: suspend (String) -> Int,
    onSetCategory: suspend (String, String) -> CategorySetResult,
    onDone: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            displayDescription(txn.description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(documentDateCompact(txn.txnDate), style = LegionType.stamp, color = sem.faint)
            Text(
                formatMoney(txn.amountCents, txn.currency),
                style = LegionType.amount,
                color = if (txn.amountCents > 0) sem.credit else sem.debit,
                modifier = Modifier.padding(start = 8.dp),
            )
            TextButton(onClick = onToggleExpanded) {
                Text(
                    if (expanded) "CANCEL" else "MOVE",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        val note = LedgerCategoryResolver.rowNote(txn)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, style = LegionType.stamp, color = sem.estimated)
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            RecategorizePanel(
                txn = txn,
                categoryNames = categoryNames,
                onPreviewCount = onPreviewRecategorizeCount,
                onSetCategory = onSetCategory,
                onDone = onDone,
            )
        }
    }
}

/**
 * The hand recategorise panel (Kevin 2026-08-07: "I drilled into Shopping, saw my two Petco
 * charges, and had no way to move them"). Opened from [CategoryDrilldownRow]'s MOVE button.
 *
 * **The merchant key is a real decision, not a formality.** [txn]'s raw description
 * (`PETCO 5421 08/01 PURCHASE CYPRESS TX`) would match only that one exact row and leave its
 * sibling (`PETCO 5421 CYPRESS TX`) behind, and would write a rule that never fires again once
 * next month's description differs by even a digit. [keyText] instead starts from
 * [LedgerRecategorizeResolver.defaultKey] - the same [com.kevin.legion.ledger.extractMerchantKey]
 * derivation `categorize_transactions` already groups by - so hand and voice paths agree, and it
 * stays fully driver-editable: [onPreviewCount] re-runs live as the field changes, so what gets
 * applied is never a silent `LIKE` over every stored description sight-unseen.
 *
 * [onSetCategory] is a direct pass-through to
 * [com.kevin.legion.ledger.LedgerController.setCategory] - this panel owns none of that function's
 * own floor-check/blast-radius/rule-replace behaviour, it only surfaces [CategorySetResult] back to
 * the driver in words.
 */
@Composable
private fun RecategorizePanel(
    txn: LedgerTransaction,
    categoryNames: List<String>,
    onPreviewCount: suspend (String) -> Int,
    onSetCategory: suspend (String, String) -> CategorySetResult,
    onDone: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val scope = rememberCoroutineScope()
    // Keyed by txn.id so re-opening the panel on a DIFFERENT row (the caller closes any other
    // panel first, but this guards the same composable being reused across a LazyColumn recycle)
    // always starts from that row's own default, never a stale edit left over from another row.
    var keyText by remember(txn.id) { mutableStateOf(LedgerRecategorizeResolver.defaultKey(txn.description)) }
    var selectedCategory by remember(txn.id) {
        mutableStateOf(txn.category?.takeIf { it in categoryNames } ?: categoryNames.firstOrNull().orEmpty())
    }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var previewCount by remember { mutableStateOf<Int?>(null) }
    var applying by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val longEnough = LedgerRecategorizeResolver.isKeyLongEnough(keyText)
    // 2026-08-13 fix: mirrors LedgerController.setCategory's noise-key refusal so the panel never
    // shows a live blast-radius count or an enabled APPLY for "CHECKCARD"/"CHKCARD"/"PURCHASE" -
    // the exact bank-boilerplate word that silently re-filed 48 unrelated transactions before.
    val bankNoise = LedgerRecategorizeResolver.isKeyBankNoise(keyText)
    val applyAllowed = longEnough && !bankNoise

    // Re-queries the blast radius every time the typed key changes - cheap, read-only
    // (LedgerController.previewRecategorizeCount never writes), and it is exactly what makes this
    // a PREVIEW rather than a blind commit.
    LaunchedEffect(keyText) {
        previewCount = if (applyAllowed) onPreviewCount(keyText) else null
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = keyText,
            onValueChange = {
                keyText = it
                resultMessage = null
            },
            singleLine = true,
            label = { Text("Merchant key", style = LegionType.stamp) },
            modifier = Modifier.fillMaxWidth(),
        )
        // ADVISORY (ticket 13 re-home): a blocked apply, not a failed gate.
        Text(
            recategorizePreviewSentence(keyText, longEnough, bankNoise, previewCount),
            style = LegionType.stamp,
            color = if (!applyAllowed) sem.estimated else sem.faint,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
        Box {
            TextButton(onClick = { categoryMenuExpanded = true }) {
                Text(
                    selectedCategory.ifBlank { "Pick a category" },
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // D14: the fixed, Room-stored list only - never free text, matching the same boundary
            // `set_category`/CategoryAgent are held to at the voice door.
            DropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                categoryNames.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            selectedCategory = name
                            categoryMenuExpanded = false
                        },
                    )
                }
            }
        }
        if (resultMessage != null) {
            Text(resultMessage.orEmpty(), style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(top = 2.dp))
        }
        Row(Modifier.padding(top = 4.dp)) {
            TextButton(
                enabled = applyAllowed && selectedCategory.isNotBlank() && !applying,
                onClick = {
                    applying = true
                    scope.launch {
                        val result = onSetCategory(keyText, selectedCategory)
                        applying = false
                        resultMessage = recategorizeResultMessage(result, keyText)
                        // Only close the panel on an ACTUAL write - a refusal or a zero-row match
                        // stays open with the reason showing, rather than silently vanishing as if
                        // it had worked.
                        if (result.rowsTouched > 0) onDone()
                    }
                },
            ) { Text("APPLY", style = LegionType.stamp) }
            TextButton(onClick = onDone, enabled = !applying) {
                Text("CANCEL", style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}

/**
 * The plain-words preview line shown as the driver edits [keyText], before anything is applied.
 * `internal`, not `private`, so it's a plain JUnit-testable pure function rather than logic buried
 * inside a composable body.
 */
internal fun recategorizePreviewSentence(
    keyText: String,
    longEnough: Boolean,
    bankNoise: Boolean,
    previewCount: Int?,
): String = when {
    !longEnough -> "Too short - needs at least ${LedgerController.MIN_MERCHANT_KEY_LENGTH} characters"
    // 2026-08-13 fix: checked after the length floor (a noise word is well past
    // MIN_MERCHANT_KEY_LENGTH) and before the preview count - a bank-boilerplate key must never
    // show a live blast-radius number, which would look like a real, actionable preview.
    bankNoise -> "That's a transaction type the bank prints, not a merchant - it would match nearly every card purchase"
    previewCount == null -> "Checking..."
    previewCount == 1 -> "Will move 1 transaction matching \"${LedgerRecategorizeResolver.normalizedKey(keyText)}\", and remember it for future imports"
    else -> "Will move $previewCount transactions matching \"${LedgerRecategorizeResolver.normalizedKey(keyText)}\", and remember it for future imports"
}

/**
 * The plain-words result line shown after [LedgerController.setCategory] actually runs -
 * [CategorySetResult.keyTooShort] and a long-enough key that simply matched nothing are DIFFERENT
 * claims (see that field's own doc comment) and must not be conflated into one message.
 * `internal`, not `private`, for the same plain-JUnit-testability reason as
 * [recategorizePreviewSentence].
 */
internal fun recategorizeResultMessage(result: CategorySetResult, keyText: String): String = when {
    result.keyTooShort -> "Key too short - nothing changed."
    // 2026-08-13 fix: a THIRD distinct refusal reason (see CategorySetResult.isNoiseKey's doc
    // comment) - conflating this with "matched nothing" would tell the driver the wrong thing
    // about why nothing happened.
    result.isNoiseKey -> "That's a bank transaction type, not a merchant - nothing changed."
    result.rowsTouched == 0 -> "No transactions matched \"${LedgerRecategorizeResolver.normalizedKey(keyText)}\"."
    result.rowsTouched == 1 -> "Moved 1 transaction."
    else -> "Moved ${result.rowsTouched} transactions."
}

// ------------------------------------------------------------------------ previews

private val previewCategorized = LedgerTransaction(
    id = 1,
    sourceFile = "eStmt_2026-07.pdf",
    accountId = "5555555555557823",
    currency = LedgerCurrency.USD,
    txnDate = System.currentTimeMillis(),
    description = "SYLAMORE CREEK CAMP",
    amountCents = -12100,
    balanceCents = null,
    lineRef = "1",
    ingestMethod = IngestMethod.DETERMINISTIC,
    category = "Travel",
)

private val previewCategoryNames = listOf("Dining Out", "Groceries", "Pets", "Shopping", "Travel")

// ticket 03: today's UTC month, matching previewCategorized's own System.currentTimeMillis()
// txnDate, so the chart's bars land inside the month it's drawn for rather than off the edge.
private val previewMonth = YearMonth.now(ZoneOffset.UTC)

/**
 * 2026-08-18 fix: a sample [BudgetVsActual] for the uncategorised bucket's own preview, exercising
 * the new real-category-breakdown chart with a real uncategorised total sitting well above zero -
 * this is the exact "uncategorized dominates" shape Kevin reported, and the fix's own preview
 * should show it still stating that total in words even though it is now excluded from the chart.
 */
private val previewBudgetForUncategorizedBucket = BudgetVsActual(
    entity = LedgerEntity.US,
    month = previewMonth,
    lines = listOf(
        BudgetLine(
            category = "Groceries",
            gap = com.kevin.legion.plan.PlanGap(target = 60_000L, actual = 41_200L, gap = 18_800L, tier = com.kevin.legion.plan.TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        ),
        BudgetLine(
            category = "Dining Out",
            gap = com.kevin.legion.plan.PlanGap(target = 20_000L, actual = 24_500L, gap = -4_500L, tier = com.kevin.legion.plan.TrustTier.PROVEN),
            hasProvisionalRows = false,
            hasPendingCategoryGuesses = false,
        ),
    ),
    uncategorized = com.kevin.legion.ledger.UncategorizedSpend(spentCents = 812_300L, hasProvisionalRows = false),
    coverage = emptyList(),
    excludedOwnAccountMovements = com.kevin.legion.ledger.ExcludedOwnAccountMovements(0, 0L, emptyList()),
)

@Preview(name = "Category drilldown: a normal category", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCategoryDrilldown() = LegionTheme {
    CategoryDrilldownScreen(
        category = "Travel",
        entity = LedgerEntity.US,
        transactions = listOf(
            previewCategorized,
            previewCategorized.copy(
                id = 2, description = "AMAZON PRIME", amountCents = -1623,
                ingestMethod = IngestMethod.UNRECONCILED,
            ),
            previewCategorized.copy(
                id = 3, description = "MYSTERY CAFE", amountCents = -845, categoryPending = true,
            ),
            previewCategorized.copy(
                id = 4, description = "LOGGED CHARGE", amountCents = -500, pendingLoggedAt = System.currentTimeMillis(),
            ),
        ),
        loading = false,
        categoryNames = previewCategoryNames,
        month = previewMonth,
        // Empty here on purpose - a preview with no coverage list still exercises the kit's
        // "outside every covered range is a gap" branch on every day without a real sample,
        // matching what a category with only sparse rows and no coverage data yet would show.
        coverage = emptyList(),
        // A real category never reads `budget` (see CategoryDrilldownScreen's own doc comment) -
        // null is the same safe no-op the real code path treats it as.
        budget = null,
        // ticket 09: an existing $150 target, so this preview also exercises the words line's
        // "target $X since <month>" branch, not just the affordance's presence.
        currentTargetCents = 15_000L,
        setTargetErrorText = null,
        setTargetSuccessNonce = 0,
        onSetTarget = {},
        onPreviewRecategorizeCount = { 2 },
        onSetCategory = { _, _ -> CategorySetResult(rowsTouched = 2, merchantsTouched = 1) },
        onBack = {},
    )
}

@Preview(name = "Category drilldown: uncategorised bucket, empty", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCategoryDrilldownUncategorizedEmpty() = LegionTheme {
    CategoryDrilldownScreen(
        category = null,
        entity = LedgerEntity.US,
        transactions = emptyList(),
        loading = false,
        categoryNames = previewCategoryNames,
        month = previewMonth,
        coverage = emptyList(),
        // `budget` still loading - the new real-category chart renders nothing rather than crash
        // or show stale figures, matching CategoryDrilldownScreen's own doc comment.
        budget = null,
        // ticket 09: the uncategorised bucket never shows the affordance at all - see
        // CategoryDrilldownScreen's own doc comment - so these three are unreachable here, wired to
        // a safe default only because the function signature requires them.
        currentTargetCents = null,
        setTargetErrorText = null,
        setTargetSuccessNonce = 0,
        onSetTarget = {},
        onPreviewRecategorizeCount = { 0 },
        onSetCategory = { _, _ -> CategorySetResult(rowsTouched = 0, merchantsTouched = 0) },
        onBack = {},
    )
}

@Preview(name = "Category drilldown: uncategorised bucket, real-category chart (2026-08-18 fix)", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewCategoryDrilldownUncategorizedWithChart() = LegionTheme {
    CategoryDrilldownScreen(
        category = null,
        entity = LedgerEntity.US,
        transactions = listOf(
            previewCategorized.copy(id = 10, description = "MOBILE BANKING PAYMENT TO CRD", amountCents = -81230, category = null),
        ),
        loading = false,
        categoryNames = previewCategoryNames,
        month = previewMonth,
        coverage = emptyList(),
        // The exact shape Kevin reported: a large uncategorised total, real categories present too
        // - this preview is what proves the chart draws THOSE, not the uncategorised bucket itself.
        budget = previewBudgetForUncategorizedBucket,
        currentTargetCents = null,
        setTargetErrorText = null,
        setTargetSuccessNonce = 0,
        onSetTarget = {},
        onPreviewRecategorizeCount = { 0 },
        onSetCategory = { _, _ -> CategorySetResult(rowsTouched = 0, merchantsTouched = 0) },
        onBack = {},
    )
}

@Preview(name = "Category drilldown: loading", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCategoryDrilldownLoading() = LegionTheme {
    CategoryDrilldownScreen(
        category = "Groceries",
        entity = LedgerEntity.US,
        transactions = emptyList(),
        loading = true,
        categoryNames = previewCategoryNames,
        month = previewMonth,
        coverage = emptyList(),
        budget = null,
        // ticket 09: no target set yet - exercises the words line's "no target set" branch.
        currentTargetCents = null,
        setTargetErrorText = null,
        setTargetSuccessNonce = 0,
        onSetTarget = {},
        onPreviewRecategorizeCount = { 0 },
        onSetCategory = { _, _ -> CategorySetResult(rowsTouched = 0, merchantsTouched = 0) },
        onBack = {},
    )
}

@Preview(name = "Category drilldown: SET TARGET rejected, reason shown in words", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCategoryDrilldownSetTargetRejected() = LegionTheme {
    Column {
        SetTargetRow(
            currentTargetCents = 0L,
            entity = LedgerEntity.US,
            month = previewMonth,
            errorText = dollarsParseErrorMessage(),
            successNonce = 0,
            onSet = {},
        )
    }
}

@Preview(name = "Category drilldown: MOVE panel open on the Petco row, two rows matched", widthDp = 360, heightDp = 640)
@Composable
private fun PreviewCategoryDrilldownRecategorizing() = LegionTheme {
    Column {
        CategoryDrilldownRow(
            txn = previewCategorized.copy(
                id = 5, description = "PETCO 5421 CYPRESS TX", amountCents = -4599, category = "Shopping",
            ),
            expanded = true,
            onToggleExpanded = {},
            categoryNames = previewCategoryNames,
            onPreviewRecategorizeCount = { 2 },
            onSetCategory = { _, _ -> CategorySetResult(rowsTouched = 2, merchantsTouched = 1) },
            onDone = {},
        )
    }
}
