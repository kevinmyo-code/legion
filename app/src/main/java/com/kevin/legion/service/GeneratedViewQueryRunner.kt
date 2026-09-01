package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.pantry.PantryController
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * `.scratch/one-today/issues/06-a-generated-view-for-niche-questions.md`. Runs a validated
 * [GeneratedViewQuerySpec] against the SAME read paths every other surface uses -
 * [LedgerController.monthOperatingExpenses] and [PantryController.recentReceiptsWithItems] - and
 * never a new DAO query (the ticket's own instruction). This is the one place in the feature a
 * number is actually produced; [GeneratedViewController] never sees a figure the model wrote.
 *
 * **Every LEDGER read goes through [LedgerController.monthOperatingExpenses]**, the same
 * transfer-exclusion / expense-only definition of spend [com.kevin.legion.ledger.LedgerBudget]'s
 * own doc comments insist every caller share ("one definition of spend... never a parallel
 * aggregate that could drift from the exclusion rules"). What this file adds on top, deliberately
 * narrower than that shared definition: **[IngestMethod.UNRECONCILED] rows are excluded from every
 * total here**, never merely flagged REPORTED the way the Money tab's own budget screens do. The
 * ticket's own rule 2 is explicit and binding for this new surface: "`UNRECONCILED` rows are
 * excluded from any total and that exclusion is stated in words on screen." [PANTRY] mirrors it
 * with [PantryReceipt.unaccountedCents] `!= null` as the same "never faced the gate" signal
 * [com.kevin.legion.pantry.PantryController.totalSpendCentsByCurrency]'s own `hasUnreconciled`
 * already reads it as.
 *
 * LEDGER always reads [LedgerEntity.US] - the closed vocabulary this ticket names is
 * shape/source/aggregation/window/grouping, not an entity/currency dimension, so this is a known,
 * deliberate narrowing rather than an oversight: a second entity is a future ticket's parameter,
 * not a silent default nobody decided.
 */
object GeneratedViewQueryRunner {

    sealed class RunResult {
        data class Rendered(val payload: GeneratedViewPayload) : RunResult()
        /** [reason] names what could not be answered - CLAUDE.md §7's "a failure result says in
         * words what did not happen", applied to a query rather than an action. */
        data class Refusal(val reason: String) : RunResult()
    }

    private val LEDGER_ENTITY = LedgerEntity.US

    suspend fun run(context: Context, spec: GeneratedViewQuerySpec): RunResult {
        val months = monthsInWindow(spec.window)

        return when (spec.source) {
            QuerySource.LEDGER -> runLedger(context, spec, months)
            QuerySource.PANTRY -> runPantry(context, spec, months)
        }
    }

    // ------------------------------------------------------------------------------------ LEDGER

    private suspend fun runLedger(
        context: Context,
        spec: GeneratedViewQuerySpec,
        months: List<YearMonth>,
    ): RunResult {
        if (spec.shape == GeneratedViewShape.LINE_SERIES || spec.shape == GeneratedViewShape.BAR_SERIES) {
            if (spec.grouping == QueryGrouping.NONE) {
                return RunResult.Refusal(
                    "A ${spec.shape.name.lowercase().replace('_', ' ')} needs a grouping - by month or by " +
                        "category - not a single ungrouped figure.",
                )
            }
        } else if (spec.grouping == QueryGrouping.BY_MONTH) {
            // TOTAL_WITH_ROWS + NONE (one figure) and TOTAL_WITH_ROWS + BY_CATEGORY (one figure
            // plus a per-category breakdown) both make sense; a month-by-month breakdown does not
            // fit a single total-with-rows view - that is what BAR_SERIES/LINE_SERIES are for.
            return RunResult.Refusal(
                "A total-with-rows view doesn't break the ledger out by month - ask for a series instead.",
            )
        }

        return when (spec.grouping) {
            QueryGrouping.BY_MONTH -> ledgerByMonth(context, spec, months)
            QueryGrouping.BY_CATEGORY -> ledgerByCategory(context, spec, months)
            QueryGrouping.NONE -> ledgerTotal(context, spec, months)
        }
    }

    private suspend fun ledgerByMonth(
        context: Context,
        spec: GeneratedViewQuerySpec,
        months: List<YearMonth>,
    ): RunResult {
        if (months.size <= 1) {
            return RunResult.Refusal(
                "By-month grouping needs a window of more than one month - THIS_MONTH only has one.",
            )
        }
        if (spec.aggregation != QueryAggregation.SUM) {
            return RunResult.Refusal("Grouping ledger spend by month only supports summing, not counting.")
        }

        var excludedCount = 0
        var excludedCents = 0L
        val points = months.map { month ->
            val rows = LedgerController.monthOperatingExpenses(context, LEDGER_ENTITY, month)
            val (kept, excluded) = rows.partition { it.ingestMethod != IngestMethod.UNRECONCILED }
            excludedCount += excluded.size
            excludedCents += excluded.sumOf { -it.amountCents }
            GeneratedViewPoint(label = month.month.name.take(3), valueCents = kept.sumOf { -it.amountCents })
        }

        val allEmpty = points.all { it.valueCents == 0L } && excludedCount == 0
        return RunResult.Rendered(
            GeneratedViewPayload(
                shape = spec.shape,
                title = spec.title,
                points = if (allEmpty) emptyList() else points,
                provenanceText = ledgerProvenance(months.size, excludedCount, excludedCents),
            ),
        )
    }

    private suspend fun ledgerByCategory(
        context: Context,
        spec: GeneratedViewQuerySpec,
        months: List<YearMonth>,
    ): RunResult {
        if (spec.window != QueryWindow.THIS_MONTH) {
            return RunResult.Refusal("Category breakdown only supports this month's spend right now.")
        }
        if (spec.aggregation != QueryAggregation.SUM) {
            return RunResult.Refusal("Category breakdown only supports summing, not counting.")
        }
        if (spec.shape == GeneratedViewShape.LINE_SERIES) {
            return RunResult.Refusal("A line needs a time axis - ask for a bar chart or a total with rows for categories.")
        }

        val rows = LedgerController.monthOperatingExpenses(context, LEDGER_ENTITY, months.single())
        val (kept, excluded) = rows.partition { it.ingestMethod != IngestMethod.UNRECONCILED }
        val excludedCents = excluded.sumOf { -it.amountCents }

        if (kept.isEmpty()) {
            return RunResult.Rendered(
                GeneratedViewPayload(
                    shape = spec.shape,
                    title = spec.title,
                    provenanceText = ledgerProvenance(1, excluded.size, excludedCents),
                ),
            )
        }

        val byCategory: Map<String, List<LedgerTransaction>> = kept.groupBy { it.category ?: "Uncategorized" }
        val ordered = byCategory.entries.sortedByDescending { (_, rows2) -> rows2.sumOf { -it.amountCents } }
        val grandTotalCents = kept.sumOf { -it.amountCents }

        return RunResult.Rendered(
            GeneratedViewPayload(
                shape = spec.shape,
                title = spec.title,
                points = if (spec.shape == GeneratedViewShape.BAR_SERIES) {
                    ordered.map { (category, rows2) ->
                        GeneratedViewPoint(label = category, valueCents = rows2.sumOf { -it.amountCents })
                    }
                } else {
                    emptyList()
                },
                totalLabel = if (spec.shape == GeneratedViewShape.TOTAL_WITH_ROWS) {
                    formatMoney(grandTotalCents, LEDGER_ENTITY.currency)
                } else {
                    null
                },
                rows = if (spec.shape == GeneratedViewShape.TOTAL_WITH_ROWS) {
                    ordered.map { (category, rows2) ->
                        GeneratedViewRow(label = category, value = formatMoney(rows2.sumOf { -it.amountCents }, LEDGER_ENTITY.currency))
                    }
                } else {
                    emptyList()
                },
                provenanceText = ledgerProvenance(1, excluded.size, excludedCents),
            ),
        )
    }

    private suspend fun ledgerTotal(
        context: Context,
        spec: GeneratedViewQuerySpec,
        months: List<YearMonth>,
    ): RunResult {
        val allRows = months.flatMap { LedgerController.monthOperatingExpenses(context, LEDGER_ENTITY, it) }
        val (kept, excluded) = allRows.partition { it.ingestMethod != IngestMethod.UNRECONCILED }
        val excludedCents = excluded.sumOf { -it.amountCents }

        if (kept.isEmpty() && excluded.isEmpty()) {
            return RunResult.Rendered(
                GeneratedViewPayload(
                    shape = spec.shape,
                    title = spec.title,
                    provenanceText = ledgerProvenance(months.size, 0, 0L),
                ),
            )
        }

        val headline = when (spec.aggregation) {
            QueryAggregation.SUM -> formatMoney(kept.sumOf { -it.amountCents }, LEDGER_ENTITY.currency)
            QueryAggregation.COUNT -> "${kept.size} transaction${if (kept.size == 1) "" else "s"}"
        }
        val rows = kept.sortedByDescending { it.txnDate }.take(MAX_ROWS).map {
            GeneratedViewRow(label = it.description, value = formatMoney(-it.amountCents, LEDGER_ENTITY.currency))
        }

        return RunResult.Rendered(
            GeneratedViewPayload(
                shape = spec.shape,
                title = spec.title,
                totalLabel = headline,
                rows = rows,
                provenanceText = ledgerProvenance(months.size, excluded.size, excludedCents),
            ),
        )
    }

    private fun ledgerProvenance(monthCount: Int, excludedCount: Int, excludedCents: Long): String {
        val counted = "Counted every reconciled ledger transaction across " +
            "$monthCount month${if (monthCount == 1) "" else "s"}."
        val excludedSentence = if (excludedCount == 0) {
            "Nothing was excluded."
        } else {
            "$excludedCount unreconciled transaction${if (excludedCount == 1) "" else "s"} " +
                "(${formatMoney(excludedCents, LEDGER_ENTITY.currency)}) never faced the reconciliation gate " +
                "and are excluded from this total."
        }
        return "$counted $excludedSentence"
    }

    // ------------------------------------------------------------------------------------ PANTRY

    private suspend fun runPantry(
        context: Context,
        spec: GeneratedViewQuerySpec,
        months: List<YearMonth>,
    ): RunResult {
        if (spec.grouping == QueryGrouping.BY_CATEGORY) {
            return RunResult.Refusal("Groceries have no category breakdown to ask for - only a store and a date.")
        }
        if (spec.shape != GeneratedViewShape.TOTAL_WITH_ROWS && spec.grouping == QueryGrouping.NONE) {
            return RunResult.Refusal(
                "A ${spec.shape.name.lowercase().replace('_', ' ')} needs by-month grouping, not a single figure.",
            )
        }
        if (spec.shape == GeneratedViewShape.TOTAL_WITH_ROWS && spec.grouping == QueryGrouping.BY_MONTH) {
            return RunResult.Refusal("A total-with-rows view for groceries takes no grouping - ask for a series instead.")
        }

        val fromMs = months.first().atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val toMs = System.currentTimeMillis()
        val receipts = PantryController.recentReceiptsWithItems(context, limitReceipts = MAX_RECEIPT_SCAN)
            .map { it.first }
            .filter { it.purchaseDate in fromMs..toMs }

        val currencies = receipts.map { it.currency }.distinct()
        if (currencies.size > 1) {
            return RunResult.Refusal(
                "Groceries in this window span more than one currency - narrow the window and ask again.",
            )
        }
        val currency = currencies.firstOrNull() ?: LedgerCurrency.USD

        return when (spec.grouping) {
            QueryGrouping.BY_MONTH -> pantryByMonth(spec, months, receipts, currency)
            QueryGrouping.NONE -> pantryTotal(spec, receipts, currency)
            QueryGrouping.BY_CATEGORY -> error("unreachable - refused above")
        }
    }

    private fun pantryByMonth(
        spec: GeneratedViewQuerySpec,
        months: List<YearMonth>,
        receipts: List<PantryReceipt>,
        currency: LedgerCurrency,
    ): RunResult {
        if (months.size <= 1) {
            return RunResult.Refusal(
                "By-month grouping needs a window of more than one month - THIS_MONTH only has one.",
            )
        }
        if (spec.aggregation != QueryAggregation.SUM) {
            return RunResult.Refusal("Grouping grocery spend by month only supports summing, not counting.")
        }

        var excludedCount = 0
        var excludedCents = 0L
        val points = months.map { month ->
            val inMonth = receipts.filter { YearMonth.from(Instant.ofEpochMilli(it.purchaseDate).atZone(ZoneOffset.UTC)) == month }
            val (kept, excluded) = inMonth.partition { it.unaccountedCents == null }
            excludedCount += excluded.size
            excludedCents += excluded.sumOf { it.totalCents }
            GeneratedViewPoint(label = month.month.name.take(3), valueCents = kept.sumOf { it.totalCents })
        }

        val allEmpty = points.all { it.valueCents == 0L } && excludedCount == 0
        return RunResult.Rendered(
            GeneratedViewPayload(
                shape = spec.shape,
                title = spec.title,
                points = if (allEmpty) emptyList() else points,
                provenanceText = pantryProvenance(months.size, excludedCount, excludedCents, currency),
            ),
        )
    }

    private fun pantryTotal(
        spec: GeneratedViewQuerySpec,
        receipts: List<PantryReceipt>,
        currency: LedgerCurrency,
    ): RunResult {
        val (kept, excluded) = receipts.partition { it.unaccountedCents == null }
        val excludedCents = excluded.sumOf { it.totalCents }

        if (kept.isEmpty() && excluded.isEmpty()) {
            return RunResult.Rendered(
                GeneratedViewPayload(
                    shape = spec.shape,
                    title = spec.title,
                    provenanceText = pantryProvenance(1, 0, 0L, currency),
                ),
            )
        }

        val headline = when (spec.aggregation) {
            QueryAggregation.SUM -> formatMoney(kept.sumOf { it.totalCents }, currency)
            QueryAggregation.COUNT -> "${kept.size} receipt${if (kept.size == 1) "" else "s"}"
        }
        val rows = kept.sortedByDescending { it.purchaseDate }.take(MAX_ROWS).map {
            GeneratedViewRow(label = it.store, value = formatMoney(it.totalCents, currency))
        }

        return RunResult.Rendered(
            GeneratedViewPayload(
                shape = spec.shape,
                title = spec.title,
                totalLabel = headline,
                rows = rows,
                provenanceText = pantryProvenance(1, excluded.size, excludedCents, currency),
            ),
        )
    }

    private fun pantryProvenance(monthCount: Int, excludedCount: Int, excludedCents: Long, currency: LedgerCurrency): String {
        val counted = "Counted every reconciled receipt across $monthCount month${if (monthCount == 1) "" else "s"}."
        val excludedSentence = if (excludedCount == 0) {
            "Nothing was excluded."
        } else {
            "$excludedCount unverified receipt${if (excludedCount == 1) "" else "s"} " +
                "(${formatMoney(excludedCents, currency)}) never had enough on file to reconcile and are " +
                "excluded from this total."
        }
        return "$counted $excludedSentence"
    }

    // -------------------------------------------------------------------------------------- util

    private const val MAX_ROWS = 50
    private const val MAX_RECEIPT_SCAN = 5000

    /** Whole UTC months in [window], oldest first, ending at the current month - see
     * [com.kevin.legion.ledger.LedgerController]'s own "UTC month boundaries throughout" convention. */
    internal fun monthsInWindow(window: QueryWindow): List<YearMonth> {
        val now = YearMonth.now(ZoneOffset.UTC)
        return when (window) {
            QueryWindow.THIS_MONTH -> listOf(now)
            QueryWindow.LAST_3_MONTHS -> (2 downTo 0).map { now.minusMonths(it.toLong()) }
            QueryWindow.LAST_6_MONTHS -> (5 downTo 0).map { now.minusMonths(it.toLong()) }
            QueryWindow.LAST_12_MONTHS -> (11 downTo 0).map { now.minusMonths(it.toLong()) }
            QueryWindow.THIS_YEAR -> {
                val start = YearMonth.of(now.year, 1)
                generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(now) }.toList()
            }
        }
    }
}
