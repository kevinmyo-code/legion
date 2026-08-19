package com.kevin.legion.ui.pantry

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.PantryCurrencyTotal
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.PantryReceiptSummary
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.common.DeckBar
import com.kevin.legion.ui.common.DeckBarChart
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.documentDate
import kotlin.math.roundToInt

/**
 * PANTRY-specific rows for ticket 09 (resolution §2: TREATMENT B, SEGREGATED
 * - the guardrail decision, not styling), reskinned under cyberdeck-ui ticket 19 per ticket 10's
 * answer #1 ("inherit the panels, skip the charts"). The shared, aspect-agnostic furniture
 * (`SectionHeader`, [DeckPane], [DeckRow], [DeckTag]) lives in `ui/common/`.
 *
 * **The guardrail this file exists to satisfy (CLAUDE.md §4 rule five):**
 * [PantryLineItem.caloriesKcal]/`proteinG`/`carbsG`/`fatG` are LLM guesses
 * from the product name, never printed on the receipt and never reconciled
 * against anything - unlike [PantryLineItem.totalPriceCents], which the
 * reconciliation gate (`PantryReceiptAgent`) DOES verify against the
 * receipt's own stated total before anything is written. This file's entire
 * job is making sure those two kinds of number are never visually
 * interchangeable: real prices live under `ON THE RECEIPT`, estimated macros
 * live under `ESTIMATED, NOT ON THE RECEIPT`, and the sentence between them
 * says which is which in words - colour ([LocalLegionSemantics.estimated])
 * only reinforces that, it never carries the meaning alone. Ticket 19 adds a
 * third reinforcement, an [DeckTagStyle.OUTLINE_MUTED] `EST` [DeckTag] on
 * every macro value itself, matching ticket 03's weight ladder for an
 * informational tag.
 */

/**
 * The dashed row-separator matching [com.kevin.legion.ui.notes.DashedHairline]'s pattern - see that
 * file's doc comment for why user/receipt-extracted text stays out of [DeckRow]'s own force-
 * uppercased `label` slot (item names here are extracted from the receipt, not typed by the driver,
 * but the same "content is not a label" rule applies) and duplicated locally rather than pulled
 * into `DeckPanels.kt` (ticket 19 stayed inside its named screen/row files only).
 */
@Composable
private fun PantryDashedHairline() {
    val sem = LocalLegionSemantics.current
    val dashStroke = with(LocalDensity.current) { 1.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawLine(
                color = sem.ruleFaint,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = dashStroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
            )
        },
    )
}

/**
 * The OPS-style ingest status row (ticket 19 / ticket 10 answer #1's "an OPS-style status row for
 * ingest state"). [receiptCount] is [com.kevin.legion.ui.PantryUiState.receipts]`.size` - already
 * loaded by the screen, not a new query (ticket 19's brief: "reuse existing state, do not add
 * queries"). Null while [com.kevin.legion.ui.PantryUiState.loading] is true.
 *
 * There is no cheap quarantined-count to sit beside it: CLAUDE.md §4 rule two means a quarantined
 * receipt is written NOWHERE (`PantryController.importReceipt`'s `Quarantined` branch never touches
 * Room), so there is no row to count without adding a new ingest-attempt log the ticket explicitly
 * says not to build. Said in words rather than a silently dropped field or an invented zero -
 * CLAUDE.md §4's "said in words on every surface" discipline applied to an absent number, not just
 * an unverified one.
 */
@Composable
fun PantryOpsStatusRow(receiptCount: Int?) {
    val sem = LocalLegionSemantics.current
    DeckRow(label = "RECEIPTS RECONCILED", value = receiptCount?.toString() ?: "-")
    Text(
        "Quarantined attempts are not counted here - a document that fails the gate is written nowhere (CLAUDE.md §4).",
        style = LegionType.stamp,
        color = sem.ghost,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * "GROCERY SPEND" panel (quant-viz ticket 07) - [PantryController.totalSpendCents]/
 * `totalSpendCentsByCurrency` were computed and never rendered before this ticket. Two pieces:
 *
 * - Totals: one [DeckRow] per [currencyTotals] entry, `formatMoney` (currency-labelled) rather than
 *   a bare [formatCents] - map taste call... no, this is CLAUDE.md §4's own "never combine
 *   currencies" rule (the DAO doc comment on
 *   [com.kevin.legion.data.local.PantryReceiptDao.totalSpendCentsByCurrency]), so there is
 *   deliberately no combined all-currency figure anywhere in this composable.
 * - A monthly bar chart of receipt totals, ONE currency only - [chartCurrency] picks it by receipt
 *   count - with the faint disclosure sentence shown only when there genuinely IS another currency
 *   to point at (a single-currency driver never reads "other currencies" about a set that doesn't
 *   exist).
 *
 * Receipt totals are gate-passed facts ([com.kevin.legion.pantry.PantryReceiptAgent] reconciles
 * every line item against the receipt's own printed total before anything is written), so nothing
 * here carries an estimate label - that stays exclusively on the macro rows in
 * [PantryReceiptSection], which this panel never touches.
 */
@Composable
fun PantrySpendPanel(
    currencyTotals: List<PantryCurrencyTotal>,
    allReceipts: List<PantryReceiptSummary>,
    nowMs: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
) {
    val sem = LocalLegionSemantics.current
    Column(modifier.fillMaxWidth()) {
        SectionHeader("GROCERY SPEND")
        if (currencyTotals.isEmpty()) {
            Text(
                "no receipts ingested",
                style = LegionType.stamp,
                color = sem.ghost,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        } else {
            for (total in currencyTotals) {
                DeckRow(label = total.currency.name, value = formatMoney(total.totalCents, total.currency))
            }
            val charted = chartCurrency(allReceipts)
            if (charted != null) {
                if (currencyTotals.size > 1) {
                    Text(
                        "chart shows ${charted.name} only - other currencies in the totals above",
                        style = LegionType.stamp,
                        color = sem.ghost,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                val monthly = bucketMonthlySumCents(allReceipts, charted, nowMs)
                val bars = monthly.mapIndexed { index, bar ->
                    val cents = bar.totalCents ?: return@mapIndexed null
                    DeckBar(
                        label = bar.month.month.name.take(3),
                        value = cents.toFloat(),
                        valueLabel = if (index == monthly.lastIndex) formatCents(cents) else null,
                    )
                }
                DeckBarChart(bars)
            }
        }
    }
}

/** The exact sentence the resolution mandates - the label that actually carries the meaning, not the colour. */
const val PANTRY_ESTIMATE_SENTENCE =
    "A receipt never prints nutrition. These are guesses from the product name and were not checked against anything."

/**
 * "610 kcal - 32P 48C 32F" from whichever of [PantryLineItem]'s four macro
 * fields are non-null, or "no estimate" if the extraction agent returned
 * none of them for this item (a genuinely unstated value, never rendered as
 * a blank or a fabricated zero). `internal` for direct unit testing.
 */
internal fun formatMacros(item: PantryLineItem): String {
    val kcal = item.caloriesKcal?.let { "$it kcal" }
    val grams = listOfNotNull(
        item.proteinG?.let { "${it.roundToInt()}P" },
        item.carbsG?.let { "${it.roundToInt()}C" },
        item.fatG?.let { "${it.roundToInt()}F" },
    )
    return when {
        kcal == null && grams.isEmpty() -> "no estimate"
        kcal == null -> grams.joinToString(" ")
        grams.isEmpty() -> kcal
        else -> "$kcal - ${grams.joinToString(" ")}"
    }
}

/**
 * One receipt, TREATMENT B SEGREGATED: header, the receipt's own line-item
 * prices, then - physically separated, never sharing a row - the estimated
 * macros with the mandated sentence. [receipt.totalCents]/[items] `totalPriceCents`
 * are the ONLY money in this composable; everything under the estimate
 * header is explicitly excluded from the reconciliation gate per CLAUDE.md
 * §4 rule five.
 *
 * Reskinned under ticket 19 as a [DeckPane] (ticket 10 answer #1: "inherit the panels, skip the
 * charts") - the receipt itself is the pane, `receipt.store` its header, the purchase date its
 * accent. Item rows stay outside [DeckRow] (see [PantryDashedHairline]'s doc comment: an
 * extracted product name is content, not a field label) but pick up the same dashed separator
 * [DeckRow] itself uses for its top rule, so the two row styles in this pane read as one family.
 */
@Composable
fun PantryReceiptSection(receipt: PantryReceipt, items: List<PantryLineItem>) {
    val sem = LocalLegionSemantics.current
    DeckPane(header = receipt.store, headerAccent = documentDate(receipt.purchaseDate)) {
        Text(
            "${formatMoney(receipt.totalCents, receipt.currency)}, ${items.size} item(s)",
            style = LegionType.reading,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        SectionHeader("ON THE RECEIPT")
        for (item in items) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(formatMoney(item.totalPriceCents, receipt.currency), style = LegionType.amount, color = MaterialTheme.colorScheme.onSurface)
            }
            PantryDashedHairline()
        }

        SectionHeader("ESTIMATED, NOT ON THE RECEIPT")
        Text(
            PANTRY_ESTIMATE_SENTENCE,
            style = MaterialTheme.typography.bodySmall,
            color = sem.estimated,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        for (item in items) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.weight(1f),
                )
                // Ticket 19: macro estimate values carry an OUTLINE_MUTED `EST` DeckTag (ticket
                // 03's informational tier) - a third reinforcement alongside the section header
                // and the mandated sentence above, never the only place the estimate is said.
                DeckTag("EST", DeckTagStyle.OUTLINE_MUTED)
                Text(formatMacros(item), style = LegionType.stamp, color = sem.estimated)
            }
            PantryDashedHairline()
        }
    }
}
