package com.kevin.legion.ui.pantry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.shortDate
import kotlin.math.roundToInt

/**
 * PANTRY-specific rows for ticket 09 (resolution §2: TREATMENT B, SEGREGATED
 * - the guardrail decision, not styling). The shared, aspect-agnostic
 * furniture (`SectionHeader`, `Hairline`) lives in `ui/common/CommonRows.kt`.
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
 * only reinforces that, it never carries the meaning alone.
 */

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
 */
@Composable
fun PantryReceiptSection(receipt: PantryReceipt, items: List<PantryLineItem>) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(receipt.store, shortDate(receipt.purchaseDate))
        Text(
            "${formatMoney(receipt.totalCents, receipt.currency)}, ${items.size} item(s)",
            style = LegionType.reading,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        SectionHeader("ON THE RECEIPT")
        for (item in items) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(formatMoney(item.totalPriceCents, receipt.currency), style = LegionType.amount, color = MaterialTheme.colorScheme.onSurface)
            }
            Hairline()
        }

        SectionHeader("ESTIMATED, NOT ON THE RECEIPT")
        Text(
            PANTRY_ESTIMATE_SENTENCE,
            style = MaterialTheme.typography.bodySmall,
            color = sem.estimated,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        for (item in items) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.weight(1f),
                )
                Text(formatMacros(item), style = LegionType.stamp, color = sem.estimated)
            }
            Hairline()
        }
    }
}
