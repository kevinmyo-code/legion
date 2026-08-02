package com.kevin.legion.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.ledger.BalancesSection
import com.kevin.legion.ui.ledger.LedgerEmptyCopy
import com.kevin.legion.ui.ledger.LedgerEmptyState
import com.kevin.legion.ui.ledger.LedgerTransactionRow
import com.kevin.legion.ui.ledger.QuarantineRow
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * `ledger` tab. Ticket 08 Part 5 - the read surfaces (resolution items 4-7):
 * the transaction stream (variant B "Stream"), per-currency balances, the
 * quarantine list, and the three empty states. Folder connection, scan
 * progress against `ScanState`, and the spend gate are ticket 08 Part 6 -
 * this screen never calls [com.kevin.legion.service.IngestScanner.scan]
 * itself, only reads what a previous scan or hand-picked import already
 * committed (or quarantined).
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill:
 * [LedgerScreen] is the state holder (talks to [LedgerController], owns the
 * reload/retry side effects), [LedgerContent] is plain UI state plus
 * callbacks and is what the `@Preview`s below exercise.
 */
data class LedgerUiState(
    val loading: Boolean = true,
    val transactions: List<LedgerTransaction> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val quarantined: List<IngestedFile> = emptyList(),
)

@Composable
fun LedgerScreen(onOpenImport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(LedgerUiState()) }
    // Bumped after a retry commits, to key the reload LaunchedEffect below -
    // a plain boolean/Unit key can't distinguish "reload once" from "reload
    // again", the same shape as MainActivity's deepLinkNonce.
    var reloadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(reloadNonce) {
        state = LedgerUiState(
            loading = false,
            transactions = LedgerController.recentTransactions(context),
            balances = LedgerController.accountBalances(context),
            quarantined = LedgerController.quarantinedFiles(context),
        )
    }

    LedgerContent(
        state = state,
        onOpenImport = onOpenImport,
        onRetryQuarantine = { driveFileId ->
            scope.launch {
                LedgerController.retryQuarantined(context, driveFileId)
                reloadNonce++
            }
        },
    )
}

/** Plain UI: [state] plus callbacks, no [LedgerController] reference - see the file doc comment. */
@Composable
fun LedgerContent(
    state: LedgerUiState,
    onOpenImport: () -> Unit,
    onRetryQuarantine: (driveFileId: String) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("LEDGER", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onOpenImport) {
                    Text("IMPORT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }

            when {
                state.loading -> Text(
                    "Loading...",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                // Nothing has ever been imported by any path (scan or hand
                // pick) and nothing is quarantined either. This is the one
                // of the three empty states this screen can actually tell
                // apart today - "nothing new" and "folder looks empty" both
                // depend on a real scan/ScanState pass, which is ticket 08
                // Part 6's job. The other two copies still exist
                // ([LedgerEmptyCopy], previewed in `ui/ledger/LedgerRows.kt`)
                // for Part 6 to wire up once that signal exists.
                state.transactions.isEmpty() && state.quarantined.isEmpty() -> LedgerEmptyState(
                    title = LedgerEmptyCopy.NO_FOLDER_TITLE,
                    body = LedgerEmptyCopy.NO_FOLDER_BODY,
                    actionLabel = "Import a statement",
                    onAction = onOpenImport,
                )
                else -> LedgerListing(state, onRetryQuarantine)
            }
        }
    }
}

@Composable
private fun LedgerListing(state: LedgerUiState, onRetryQuarantine: (driveFileId: String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (state.balances.isNotEmpty()) {
            item(key = "balances-header") { SectionHeader("BALANCES") }
            item(key = "balances") { BalancesSection(state.balances) }
        }

        if (state.quarantined.isNotEmpty()) {
            item(key = "quarantine-header") { SectionHeader("NEEDS ATTENTION", state.quarantined.size.toString()) }
            items(state.quarantined, key = { "q-${it.driveFileId}" }) { file ->
                QuarantineRow(file, onRetryQuarantine)
                Hairline()
            }
        }

        if (state.transactions.isNotEmpty()) {
            item(key = "activity-header") { SectionHeader("RECENT ACTIVITY") }
            items(state.transactions, key = { "t-${it.id}" }) { txn ->
                LedgerTransactionRow(txn)
                Hairline()
            }
        }
    }
}

/**
 * `ledger/import` - absorbed from the deleted `LedgerImportActivity`. Content
 * unchanged (`ACTION_OPEN_DOCUMENT` picks a PDF, [LedgerController] ingests
 * it, the result - success/count, or the quarantine reason - is shown); only
 * the hosting changed, and the activity-result launcher moved from
 * `registerForActivityResult` (needs an Activity) to
 * `rememberLauncherForActivityResult` (the Compose-native equivalent, now
 * that this is a screen inside the shell rather than its own Activity).
 */
@Composable
fun LedgerImportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf("Pick a bank statement PDF to import.") }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingUri = uri
    }

    LaunchedEffect(pendingUri) {
        val current = pendingUri ?: return@LaunchedEffect
        status = "Importing..."
        status = LedgerController.importStatement(context, current).message
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Button(onClick = onBack) {
                Text("< Back")
            }
            Text(status)
            Button(onClick = { pickPdf.launch(arrayOf("application/pdf")) }) {
                Text("Pick statement PDF")
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Ledger: loading", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerLoading() = LegionTheme {
    LedgerContent(LedgerUiState(loading = true), onOpenImport = {}, onRetryQuarantine = {})
}

@Preview(name = "Ledger: no folder connected (only empty state this screen can tell apart)", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerEmpty() = LegionTheme {
    LedgerContent(LedgerUiState(loading = false), onOpenImport = {}, onRetryQuarantine = {})
}

@Preview(name = "Ledger: balances + quarantine + stream", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewLedgerPopulated() = LegionTheme {
    LedgerContent(
        state = LedgerUiState(
            loading = false,
            balances = listOf(
                AccountBalance("BOFA ****4471", LedgerCurrency.USD, 119_80),
                AccountBalance("DBS ****8802", LedgerCurrency.SGD, 216_582),
            ),
            quarantined = listOf(
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
            ),
            transactions = listOf(
                LedgerTransaction(
                    id = 1,
                    sourceFile = "eStmt_2026-07.pdf",
                    accountId = "BOFA ****4471",
                    currency = LedgerCurrency.USD,
                    txnDate = System.currentTimeMillis(),
                    description = "CHECKCARD 0701 TRADER JOES #452 SAN JOSE CA",
                    amountCents = -8734,
                    balanceCents = 412_09,
                    lineRef = "1",
                    ingestMethod = com.kevin.legion.data.local.IngestMethod.DETERMINISTIC,
                ),
                LedgerTransaction(
                    id = 2,
                    sourceFile = "eStmt_2026-07.pdf",
                    accountId = "BOFA ****4471",
                    currency = LedgerCurrency.USD,
                    txnDate = System.currentTimeMillis(),
                    description = "PAYROLL DES:DIRECT DEP ID:9928471 INDN:K MYO",
                    amountCents = 384_512,
                    balanceCents = 588_87,
                    lineRef = "2",
                    ingestMethod = com.kevin.legion.data.local.IngestMethod.LLM_RECONCILED,
                ),
            ),
        ),
        onOpenImport = {},
        onRetryQuarantine = {},
    )
}
