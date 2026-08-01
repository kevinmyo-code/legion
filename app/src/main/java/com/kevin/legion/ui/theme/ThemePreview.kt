package com.kevin.legion.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Renderable proof of the Instrument theme, and the reference the four blocked
 * UI tickets build against.
 *
 * These are previews, not components. Do not import them into real screens -
 * the shared components get extracted once the ledger UI ticket settles what
 * they actually need to be. The point here is that the *tokens* are correct.
 *
 * Everything is hardcoded. Nothing touches a singleton, a database, Bluetooth,
 * or a file, so these render in the preview JVM without a `LocalInspectionMode`
 * guard. That is a deliberate carry-over from Midnight AI's L1 lesson, where a
 * helper that looked pure routed into `ObdBluetoothManager`'s throwing static
 * init and killed seven previews.
 */

private data class DemoTxn(
    val description: String,
    val stamp: String,
    val amount: String,
    val isCredit: Boolean = false,
    val readByModel: Boolean = false,
)

private val DemoRows = listOf(
    DemoTxn("Transferwise Pte Ltd", "28 JUL", "−1,240.00"),
    DemoTxn("NTUC Fairprice #04-21", "27 JUL", "−86.45"),
    DemoTxn("SP Group Utilities", "26 JUL", "−214.30", readByModel = true),
    DemoTxn("Giro Salary", "25 JUL", "+8,900.00", isCredit = true),
)

@Composable
private fun AccountBar(name: String, currency: String) {
    val semantics = LocalLegionSemantics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(name.uppercase(), style = MaterialTheme.typography.labelSmall, color = semantics.faint)
        Text(currency, style = MaterialTheme.typography.labelSmall, color = semantics.faint)
    }
    HorizontalDivider(thickness = 1.dp, color = semantics.rule)
}

@Composable
private fun LedgerRow(txn: DemoTxn) {
    val semantics = LocalLegionSemantics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                txn.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(txn.stamp, style = LegionType.stamp, color = semantics.faint)
                if (txn.readByModel) {
                    // Provenance is visible but quiet. An LLM_RECONCILED row passed
                    // the same reconciliation gate as a deterministic one, so this
                    // is an audit affordance, not a trust warning.
                    Text("LLM", style = LegionType.stamp, color = semantics.ghost)
                }
            }
        }
        Text(
            txn.amount,
            style = LegionType.amount,
            color = if (txn.isCredit) semantics.credit else semantics.debit,
        )
    }
    HorizontalDivider(thickness = 1.dp, color = semantics.ruleFaint)
}

@Composable
private fun LedgerDemo() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            AccountBar("DBS Multiplier ····4471", "SGD")
            DemoRows.forEach { LedgerRow(it) }
            AccountBar("Bank of America ····8802", "USD")
            LedgerRow(DemoTxn("Shell Oil 574410", "24 JUL", "−52.18"))
        }
    }
}

/**
 * The guardrail rendered. CLAUDE.md §4 rule five: a value the source document
 * never stated must read as an estimate. Colour alone does not satisfy that -
 * it fails in greyscale and for colour-blind users - so the label carries it
 * and the colour reinforces it.
 */
@Composable
private fun EstimateDemo() {
    val semantics = LocalLegionSemantics.current
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Greenwood Farms Whole Milk 1L",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("1 × 4.29  ·  SGD", style = LegionType.stamp, color = semantics.faint)

            Text(
                "ESTIMATED · NOT ON RECEIPT",
                style = MaterialTheme.typography.labelSmall,
                color = semantics.estimated,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                listOf("610 kcal", "32 P", "48 C", "33 F").forEach {
                    Text(it, style = LegionType.reading, color = semantics.estimated)
                }
            }
            Text(
                "The model guessed these from the product name.",
                style = MaterialTheme.typography.bodySmall,
                color = semantics.faint,
                modifier = Modifier.padding(top = 2.dp),
            )

            Text(
                "RECONCILED TO PRINTED TOTAL 86.45",
                style = MaterialTheme.typography.labelSmall,
                color = semantics.faint,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                "QUARANTINED · TOTALS DISAGREE BY 0.02",
                style = MaterialTheme.typography.labelSmall,
                color = semantics.quarantined,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Preview(name = "Ledger · dark (default)", showBackground = true)
@Composable
private fun PreviewLedgerDark() {
    LegionTheme(darkTheme = true) { LedgerDemo() }
}

@Preview(name = "Ledger · light", showBackground = true)
@Composable
private fun PreviewLedgerLight() {
    LegionTheme(darkTheme = false) { LedgerDemo() }
}

@Preview(name = "Estimates + quarantine · dark", showBackground = true)
@Composable
private fun PreviewEstimateDark() {
    LegionTheme(darkTheme = true) { EstimateDemo() }
}

@Preview(name = "Estimates + quarantine · light", showBackground = true)
@Composable
private fun PreviewEstimateLight() {
    LegionTheme(darkTheme = false) { EstimateDemo() }
}

/** Narrow case. The Oppo A17K is roughly 360dp, which is the width that hurts. */
@Preview(name = "Ledger · 320dp narrow", widthDp = 320, showBackground = true)
@Composable
private fun PreviewLedgerNarrow() {
    LegionTheme(darkTheme = true) { LedgerDemo() }
}
