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
import com.kevin.legion.ledger.formatMoney
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.documentDateCompact
import com.kevin.legion.ledger.maskedAccountLabel

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
            // Ticket 12 §7: a distinct claim from "read by AI", never reused
            // for it - an UNRECONCILED row was extracted deterministically
            // and never faced a reconciliation gate at all (no anchor
            // existed to check it against), which is a WEAKER claim than
            // LLM_RECONCILED's "passed the same gate a deterministic row
            // did, only the extraction method differed".
            if (txn.ingestMethod == IngestMethod.UNRECONCILED) {
                Spacer(Modifier.width(8.dp))
                Text("pending, not verified", style = LegionType.stamp, color = sem.faint)
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
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(balance.currency.name, style = LegionType.stamp, color = sem.faint, modifier = Modifier.width(38.dp))
            Text(maskedAccountLabel(balance.accountId), style = LegionType.stamp, color = sem.faint, modifier = Modifier.weight(1f))
            // Ticket 12 §4/§6: provisional rows count toward what's shown so
            // the figure is actually current.
            //
            // A figure containing one takes `sem.estimated`, the same role
            // pantry macros use, because it is the same kind of claim: a
            // number no source document ever stated. The label beneath is
            // still what carries the meaning - CLAUDE.md §4 rule 7 says "in
            // words", and colour alone fails for colour-blind users and in
            // greyscale. The colour makes the distinction visible at a
            // glance; it never carries it alone.
            when {
                // formatMoney, not bare formatCents (2026-08-07 currency audit):
                // the currency code to the LEFT of this row (line above) is a
                // separate Text node a screen reader or a glanced screenshot can
                // miss - the figure itself must carry its own currency too, same
                // "never rely on an adjacent label alone" posture CLAUDE.md §4
                // rule 7 already states for a provisional/unverified stamp.
                // BOTH deltas, never just one (fixed 2026-08-07, found on
                // Kevin's phone). [AccountBalance]'s own doc comment defines
                // available as `balanceCents + provisionalDeltaCents +
                // pendingDeltaCents`; this row computed only the first two, so
                // three voice-logged pending charges totalling 123.79 changed
                // the note underneath but left the headline figure at 440.68
                // while his bank said 316.89. The data was right the whole
                // time - only this arithmetic was short a term.
                // AccountBalance.availableCents, never a local sum - see that
                // property's doc comment for the bug this closes. When
                // balanceCents is null (Bank of America's card layout prints
                // none) it returns the unposted movement alone, which must
                // never be rendered as if it were a stated balance; the
                // `estimated` colour plus the words below carry that.
                balance.hasAnyFigure -> Text(
                    formatMoney(balance.availableCents, balance.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (balance.isUnconfirmed) {
                        sem.estimated
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                // null means the source statement format never printed a
                // running balance at all (Bank of America's section layout)
                // and no provisional rows exist either - that is a distinct,
                // honest "not stated" state, never rendered as 0.00.
                else -> Text("not stated", style = MaterialTheme.typography.bodySmall, color = sem.faint)
            }
        }
        // 2026-08-18 (Kevin: "if the account balance is stale"): [AccountBalance.asOfMs] is the
        // txnDate of the exact row [balanceCents] was read from, so this line says WHEN the
        // printed figure is from, not just that it might be old. Null is not "unknown" and not
        // "today" - it is the BofA-card-layout case, where no row for this account has EVER
        // printed a balance (see [AccountBalance.asOfMs]'s own doc comment), and is worded as its
        // own distinct sentence rather than silently omitted or read as current.
        Text(
            if (balance.asOfMs != null) "as of ${documentDateCompact(balance.asOfMs)}" else "no balance ever printed for this account",
            style = LegionType.stamp,
            color = sem.faint,
        )
        if (balance.isProvisional) {
            // Review finding 5: this used to branch on `balance.balanceCents
            // != null`, which reads as "has this account ever been
            // reconciled" but actually only asks "did the LATEST reconciled
            // statement happen to print a balance". BofaCardCsvStatementParser
            // never sets balanceCents on ANY row it produces
            // (BofaCardStatementParser.kt:350), so once a driver imports the
            // monthly card PDF, this branch still landed on the false "no
            // statement yet" copy every month after - the account had in
            // fact just been reconciled. `hasReconciledRows` asks the correct
            // question directly instead of inferring it from balanceCents.
            val label = when {
                balance.balanceCents != null -> "includes pending transactions not yet on a statement"
                balance.hasReconciledRows -> "pending card activity only - this account's statements never print a balance"
                else -> "no statement yet - pending transactions only, unverified"
            }
            Text(label, style = LegionType.stamp, color = sem.faint)
        }
        // Voice-logged pending transactions (the driver's OWN report, never a
        // file) - a distinct claim from isProvisional's "a file said this,
        // unconfirmed" above, so it renders as its own line, never merged
        // into the sentence above it. Words, never colour alone, per
        // CLAUDE.md §4 rule 7 - see LedgerPendingResolver.balanceNote's doc
        // comment for why the number shown is the magnitude, not the signed sum.
        if (balance.hasPendingRows) {
            Text(LedgerPendingResolver.balanceNote(balance.pendingDeltaCents), style = LegionType.stamp, color = sem.faint)
        }
    }
}

// ------------------------------------------------------------ voice-logged pending transactions

/**
 * The driver's own reports of still-processing charges/credits, never confirmed by any statement
 * or export - REPORTED tier (CLAUDE.md §4 rule 7). Only rendered by the caller when [pending] is
 * non-empty (`LedgerScreen`'s `LedgerListing`, matching every other conditional section there).
 * Each row can be cleared individually via [onClear] - the row doesn't know or care how the
 * removal is actually persisted, same callback-out discipline [QuarantineRow]'s RETRY uses.
 */
@Composable
fun PendingTransactionsSection(pending: List<LedgerTransaction>, onClear: (id: Long) -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        for (row in pending) {
            PendingTransactionRow(row, onClear)
            Hairline()
        }
    }
}

@Composable
private fun PendingTransactionRow(row: LedgerTransaction, onClear: (id: Long) -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(displayDescription(row.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(documentDateCompact(row.txnDate), style = LegionType.stamp, color = sem.faint)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatMoney(row.amountCents, row.currency),
                    style = LegionType.amount,
                    color = if (row.amountCents > 0) sem.credit else sem.debit,
                )
                Spacer(Modifier.width(8.dp))
                Text("pending, not verified", style = LegionType.stamp, color = sem.faint)
            }
        }
        TextButton(onClick = { onClear(row.id) }) {
            Text("REMOVE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// -------------------------------------------------------- category guesses (ticket B2, 2026-08-07)

/**
 * One merchant's pending AI-guessed category, up for confirm or correction. Never a colour-only
 * "guessed" indicator (CLAUDE.md §4 rule 7) - the word "guessed" and "not confirmed" both appear
 * in the row's own text.
 */
@Composable
fun CategoryGuessesSection(guesses: List<LedgerCategoryResolver.MerchantGuess>, onConfirm: (merchantKey: String, category: String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        for (guess in guesses) {
            CategoryGuessRow(guess, onConfirm)
            Hairline()
        }
    }
}

@Composable
private fun CategoryGuessRow(guess: LedgerCategoryResolver.MerchantGuess, onConfirm: (merchantKey: String, category: String) -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${guess.merchantKey} (${guess.transactionCount})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "guessed: ${guess.category} - not confirmed",
                style = LegionType.stamp,
                color = sem.estimated,
            )
        }
        TextButton(onClick = { onConfirm(guess.merchantKey, guess.category) }) {
            Text("CONFIRM", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
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

/**
 * The bulk counterpart to [QuarantineRow]'s RETRY, sat directly under the
 * quarantine section header.
 *
 * It says what it will and will not do, because a quarantine reason describes
 * the build that wrote it: a parser fix does not re-examine the statements the
 * previous build rejected, so a list this long is usually stale rather than
 * genuinely unreadable. Flipping the records is the cheap half; the scan that
 * actually re-reads them stays a separate explicit tap, exactly as it is for a
 * single file, so this button never silently spends anything.
 */
@Composable
fun RetryAllQuarantineRow(count: Int, onRetryAll: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Re-check all $count after a parser fix, then scan again.",
            style = MaterialTheme.typography.bodySmall,
            color = sem.faint,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRetryAll) {
            Text("RETRY ALL", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
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

/** A fixed UTC-midnight instant (2026-08-12) for the "as of" previews - [documentDateCompact] reads document dates in UTC, so this must be too. */
private val PREVIEW_AS_OF_MS = java.time.LocalDate.of(2026, 8, 12).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

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

@Preview(name = "Ledger row: card CSV, pending and not verified", widthDp = 360)
@Composable
private fun PreviewTransactionRowUnreconciled() = LegionTheme {
    Surface {
        LedgerTransactionRow(
            previewTxn.copy(
                accountId = "7823",
                description = "NORTHWIND OUTFITTERS 07/13 PURCHASE SEATTLE WA",
                amountCents = -6000,
                balanceCents = null,
                ingestMethod = IngestMethod.UNRECONCILED,
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
                AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80, asOfMs = PREVIEW_AS_OF_MS),
                AccountBalance("DBS ****8802", LedgerCurrency.SGD, 216_582, asOfMs = PREVIEW_AS_OF_MS),
            ),
        )
    }
}

/**
 * 2026-08-18: the "as of" line's two states, side by side - a real printed balance with the date
 * it was read from, and the Bank of America card-CSV case where no row has ever printed one at
 * all. See [AccountBalance.asOfMs]'s own doc comment for why null is a distinct, worded state
 * rather than a missing date silently omitted.
 */
@Preview(name = "Balances: as-of date on a real printed balance", widthDp = 360)
@Composable
private fun PreviewBalanceAsOfDated() = LegionTheme {
    Surface {
        BalancesSection(listOf(AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80, asOfMs = PREVIEW_AS_OF_MS)))
    }
}

@Preview(name = "Balances: as-of - no balance ever printed", widthDp = 360)
@Composable
private fun PreviewBalanceAsOfUndated() = LegionTheme {
    Surface { BalancesSection(listOf(AccountBalance("BOFA ****4471", LedgerCurrency.USD, null, asOfMs = null))) }
}

@Preview(name = "Balances: provisional card CSV rows included, marked unverified", widthDp = 360)
@Composable
private fun PreviewBalancesProvisional() = LegionTheme {
    Surface {
        BalancesSection(
            listOf(
                AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80),
                // Card PDF never arrived yet - accountId is the bare
                // filename last-4 (ticket 12 call 2) and there is no
                // printed balance to add the delta onto, only the delta
                // itself, clearly marked. hasReconciledRows is false here:
                // no statement has EVER covered this card, not just "the
                // latest one didn't print a balance" - the "no statement
                // yet" copy is the correct one for this exact case.
                AccountBalance(
                    accountId = "7823",
                    currency = LedgerCurrency.USD,
                    balanceCents = null,
                    provisionalDeltaCents = -7500,
                    isProvisional = true,
                    hasReconciledRows = false,
                ),
            ),
        )
    }
}

/**
 * Review finding 5's exact regression case: the card PDF for a prior month
 * HAS landed (so this account is reconciled - [AccountBalance.hasReconciledRows]
 * is true), but Bank of America's card statement format never prints a
 * running balance, so [AccountBalance.balanceCents] is still null even
 * though a real statement exists. This must read "pending card activity
 * only - this account's statements never print a balance", never "no
 * statement yet" - that copy would be stating something false about an
 * account that has, in fact, been reconciled.
 */
@Preview(name = "Balances: card CSV pending, but the account HAS been reconciled before", widthDp = 360)
@Composable
private fun PreviewBalancesProvisionalReconciledCard() = LegionTheme {
    Surface {
        BalancesSection(
            listOf(
                AccountBalance(
                    accountId = "4111111111117823",
                    currency = LedgerCurrency.USD,
                    balanceCents = null,
                    provisionalDeltaCents = -1500,
                    isProvisional = true,
                    hasReconciledRows = true,
                ),
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
