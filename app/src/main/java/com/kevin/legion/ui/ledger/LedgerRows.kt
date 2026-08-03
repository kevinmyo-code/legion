package com.kevin.legion.ui.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.displayDescription
import com.kevin.legion.ledger.formatCents
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.documentDateCompact

/**
 * Ledger-specific read-surface rows for ticket 08 Parts 4-7 (resolution:
 * `.scratch/ledger-drive-ingestion/issues/08-ledger-ui.md`). The shared,
 * aspect-agnostic furniture (`SectionHeader`, `Hairline`, `ReadingRow`,
 * `NotBuiltRow`) lives in `ui/common/CommonRows.kt` instead - these are the
 * pieces that are genuinely ledger-shaped: a transaction, a balance, a
 * quarantined file, an empty state.
 *
 * Everything here is display-only. None of it writes to the database except
 * [QuarantineRow]'s RETRY button, which calls back out to [onRetry] rather
 * than touching Room directly - the row doesn't know or care how a retry is
 * actually persisted.
 */

// ------------------------------------------------------- transaction row (item 4, variant B "Stream")

/**
 * One transaction, variant B "Stream" (resolution §4: **CHOSEN** over
 * "Statement"'s three-column layout and "Register"'s hard truncation).
 * Description gets the full row width and never truncates - on a phone the
 * merchant string is what's actually being scanned for, and truncating it
 * was the real failure the other two variants exposed. No running-balance
 * column; [com.kevin.legion.ledger.LedgerController.accountBalances] is
 * what the standalone [BalancesSection] reads instead.
 *
 * **Date gutter fix (resolution §4 fix 1):** the prototype gave the date its
 * own 38-40dp leading column and it did almost no work there - a full column
 * of width spent on four characters. This folds the date onto the amount
 * line instead (beside the amount rather than the description), which
 * recovers that width for the description and keeps the date next to the
 * other "metadata" reading (amount, provenance) rather than floating alone
 * on the left.
 *
 * **Provenance (resolution §4 fix 3):** an inline "read by AI" label for
 * [IngestMethod.LLM_RECONCILED], never a glyph (Register's `~` read as
 * cryptic in the prototype) and never colour-only.
 */
@Composable
fun LedgerTransactionRow(txn: LedgerTransaction) {
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
            Spacer(Modifier.width(8.dp))
            Text(
                formatMoney(txn.amountCents, txn.currency),
                style = LegionType.amount,
                color = if (txn.amountCents > 0) sem.credit else sem.debit,
            )
            if (txn.ingestMethod == IngestMethod.LLM_RECONCILED) {
                Spacer(Modifier.width(8.dp))
                Text("read by AI", style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}

// ------------------------------------------------------------------- balances (item 5)

/**
 * Per-currency, stacked, NO FX (resolution §5). SGD and USD are never
 * combined into one headline figure - inventing an exchange rate would be
 * exactly the unstated-value problem CLAUDE.md §4 rule five exists to
 * prevent, so the explicit sentence beneath the rows says so in words, not
 * just by omission.
 */
@Composable
fun BalancesSection(balances: List<AccountBalance>) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        for ((index, balance) in balances.withIndex()) {
            AccountBalanceRow(balance)
            if (index != balances.lastIndex) Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Not combined. No exchange rate is applied.",
            style = LegionType.stamp,
            color = sem.faint,
        )
    }
}

@Composable
private fun AccountBalanceRow(balance: AccountBalance) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(balance.currency.name, style = LegionType.stamp, color = sem.faint, modifier = Modifier.width(38.dp))
        Text(balance.accountId, style = LegionType.stamp, color = sem.faint, modifier = Modifier.weight(1f))
        // null means the source statement format never printed a running
        // balance at all (Bank of America's section layout) - that is a
        // distinct, honest "not stated" state, never rendered as 0.00.
        if (balance.balanceCents != null) {
            Text(formatCents(balance.balanceCents), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        } else {
            Text("not stated", style = MaterialTheme.typography.bodySmall, color = sem.faint)
        }
    }
}

// ---------------------------------------------------------------- quarantine (item 6, provisional)

/**
 * A statement that failed the reconciliation gate: filename, the gate's own
 * reason in plain language (never a generic "import failed"), and a RETRY
 * action (resolution §6). [semantics.quarantined] is a **leading 2dp bar**,
 * not a coloured background or row tint - CLAUDE.md §4 rule five's "colour
 * alone is never sufficient" posture applied here too: the bar marks the row
 * as needing attention, the reason text is what actually explains why.
 *
 * **Provisional** - resolution §"1,2,3,6,7": built and previewed in Studio,
 * never rendered on the A17K (the phone re-locked mid-capture during the
 * prototype session).
 */
@Composable
fun QuarantineRow(file: IngestedFile, onRetry: (driveFileId: String) -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Box(Modifier.width(2.dp).height(34.dp).background(sem.quarantined))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(file.displayName, style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
            Text(
                file.quarantineReason ?: "Didn't reconcile against the statement's own total.",
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { onRetry(file.driveFileId) }) {
            Text("RETRY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ------------------------------------------------------------------- empty states (item 7, provisional)

/**
 * One of ticket 08's three distinct empty-state messages (resolution §7,
 * provisional - same "built, not visually reviewed" status as [QuarantineRow]).
 * **Never a single generic "nothing here" message** - the three real causes
 * (no folder connected yet, a scan found nothing new, a scan found nothing
 * at all and Drive may still be syncing) read completely differently to a
 * driver and only one of them is ever routine, not a problem.
 */
@Composable
fun LedgerEmptyState(title: String, body: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = sem.faint)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onAction) {
                Text(actionLabel, style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Copy for the three states this ticket names (resolution §7). Kept as
 * named constructors rather than a raw enum so the exact wording lives in
 * one place and reads next to the resolution's own language, which is what
 * the copy is quoting.
 */
object LedgerEmptyCopy {
    /** No statements folder connected and nothing has ever been imported by hand either. */
    const val NO_FOLDER_TITLE = "No folder connected"
    const val NO_FOLDER_BODY = "Point LEGION at a Drive folder of statements, or import one by hand."

    /** A folder is connected and has been scanned; every file it holds is already accounted for. */
    const val NOTHING_NEW_TITLE = "Nothing new"
    const val NOTHING_NEW_BODY = "Everything in this folder is already imported."

    /**
     * A scan came back with literally zero files, which the probe found can
     * legitimately happen for a file uploaded moments ago - "Design for 'a
     * scan may legitimately find nothing new'" (memory/MEMORY.md, ticket 05
     * §9). This copy must never read as an error.
     */
    const val LOOKS_EMPTY_TITLE = "Folder looks empty"
    const val LOOKS_EMPTY_BODY =
        "Drive may still be syncing. A statement uploaded in the last few minutes often isn't visible yet."
}

// ------------------------------------------------------------------------ previews

private val previewTxn = LedgerTransaction(
    id = 1,
    sourceFile = "eStmt_2026-07.pdf",
    accountId = "BOFA ****4471",
    currency = LedgerCurrency.USD,
    txnDate = System.currentTimeMillis(),
    description = "CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA",
    amountCents = -8734,
    balanceCents = 412_09,
    lineRef = "1",
    ingestMethod = IngestMethod.DETERMINISTIC,
)

@Preview(name = "Ledger row: deterministic debit", widthDp = 360)
@Composable
private fun PreviewTransactionRowDeterministic() = LegionTheme {
    Surface { LedgerTransactionRow(previewTxn) }
}

@Preview(name = "Ledger row: LLM-reconciled credit", widthDp = 360)
@Composable
private fun PreviewTransactionRowLlm() = LegionTheme {
    Surface {
        LedgerTransactionRow(
            previewTxn.copy(
                description = "PAYROLL DES:DIRECT DEP ID:9928471 INDN:K MYO",
                amountCents = 384_512,
                ingestMethod = IngestMethod.LLM_RECONCILED,
            ),
        )
    }
}

@Preview(name = "Balances: SGD + USD, no FX", widthDp = 360)
@Composable
private fun PreviewBalances() = LegionTheme {
    Surface {
        BalancesSection(
            listOf(
                AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80),
                AccountBalance("DBS ****8802", LedgerCurrency.SGD, 216_582),
            ),
        )
    }
}

@Preview(name = "Balances: a format that never prints one", widthDp = 360)
@Composable
private fun PreviewBalancesUnstated() = LegionTheme {
    Surface { BalancesSection(listOf(AccountBalance("BOFA ****4471", LedgerCurrency.USD, null))) }
}

@Preview(name = "Quarantine row", widthDp = 360)
@Composable
private fun PreviewQuarantineRow() = LegionTheme {
    Surface {
        QuarantineRow(
            IngestedFile(
                driveFileId = "abc123",
                treeUri = "content://tree/x",
                displayName = "eStmt_2025-11-05.pdf",
                sizeBytes = 40_000,
                lastModified = System.currentTimeMillis(),
                contentSha256 = null,
                state = IngestState.QUARANTINED,
                quarantineReason = "Lines summed to 4,182.19 but the statement says 4,180.00.",
                firstSeenAt = System.currentTimeMillis(),
                lastAttemptAt = System.currentTimeMillis(),
            ),
            onRetry = {},
        )
    }
}

@Preview(name = "Empty: no folder connected", widthDp = 360)
@Composable
private fun PreviewEmptyNoFolder() = LegionTheme {
    Surface {
        LedgerEmptyState(
            LedgerEmptyCopy.NO_FOLDER_TITLE,
            LedgerEmptyCopy.NO_FOLDER_BODY,
            actionLabel = "Import a statement",
            onAction = {},
        )
    }
}

@Preview(name = "Empty: nothing new", widthDp = 360)
@Composable
private fun PreviewEmptyNothingNew() = LegionTheme {
    Surface { LedgerEmptyState(LedgerEmptyCopy.NOTHING_NEW_TITLE, LedgerEmptyCopy.NOTHING_NEW_BODY) }
}

@Preview(name = "Empty: folder looks empty, not an error", widthDp = 360)
@Composable
private fun PreviewEmptyLooksEmpty() = LegionTheme {
    Surface { LedgerEmptyState(LedgerEmptyCopy.LOOKS_EMPTY_TITLE, LedgerEmptyCopy.LOOKS_EMPTY_BODY) }
}
