package com.kevin.legion.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.IngestedFile
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.AccountBalance
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerFolderPreferences
import com.kevin.legion.service.FileResults
import com.kevin.legion.service.IngestScanner
import com.kevin.legion.service.LedgerIngestService
import com.kevin.legion.service.ScanState
import com.kevin.legion.service.SpendEstimate
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.ledger.BalancesSection
import com.kevin.legion.ui.ledger.FolderConnectionRow
import com.kevin.legion.ui.ledger.LedgerEmptyCopy
import com.kevin.legion.ui.ledger.LedgerEmptyState
import com.kevin.legion.ui.ledger.LedgerEmptyStateResolver
import com.kevin.legion.ui.ledger.LedgerFolderUiState
import com.kevin.legion.ui.ledger.LedgerTransactionRow
import com.kevin.legion.ui.ledger.QuarantineRow
import com.kevin.legion.ui.ledger.ScanStatusSection
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * `ledger` tab. Ticket 08 Part 5 built the read surfaces (resolution items
 * 4-7): the transaction stream (variant B "Stream"), per-currency balances,
 * the quarantine list, and (partially - see below) the three empty states.
 * Part 6, this revision, adds items 1-3 and finishes item 7: folder
 * connection, scan progress against [ScanState], and the ticket 06 spend
 * gate, plus wiring the two empty states Part 5 could build but not reach.
 *
 * **How the scan is driven.** `AriaForegroundService` used to own the
 * [IngestScanner] this screen binds to (ticket 05 resolution §1's plan), but
 * that service's `onCreate()` unconditionally boots the entire voice
 * assistant - see [com.kevin.legion.service.AriaForegroundService]'s doc
 * comment where `ingestScanner` used to be declared. Opening the Ledger tab
 * must never do that (`AssistantIgnition` promises "ledger... unaffected",
 * off by default). This screen instead binds to
 * [com.kevin.legion.service.LedgerIngestService], a small `dataSync`-only
 * service that owns nothing but the scanner - see [rememberIngestScanner]
 * and that service's own doc comment for the full reasoning. The bind is the
 * shape ticket 05 anticipated ("binding the Activity to the service"); only
 * WHICH service changed.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill:
 * [LedgerScreen] is the state holder (talks to [LedgerController]/
 * [LedgerFolderPreferences]/the bound [IngestScanner], owns every side
 * effect), [LedgerContent] is plain UI state plus callbacks and is what the
 * `@Preview`s below exercise.
 */
data class LedgerUiState(
    val loading: Boolean = true,
    val transactions: List<LedgerTransaction> = emptyList(),
    val balances: List<AccountBalance> = emptyList(),
    val quarantined: List<IngestedFile> = emptyList(),
    val folder: LedgerFolderUiState = LedgerFolderUiState.NotConnected,
    val scanState: ScanState = ScanState.Idle,
    val hasGeminiKey: Boolean = true,
)

@Composable
fun LedgerScreen(onOpenImport: () -> Unit, onOpenKeySettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(LedgerUiState()) }
    // Bumped after a retry commits, or after a scan finishes, to key the
    // reload LaunchedEffect below - a plain boolean/Unit key can't
    // distinguish "reload once" from "reload again", the same shape as
    // MainActivity's deepLinkNonce.
    var reloadNonce by remember { mutableStateOf(0) }

    val ingestService = rememberIngestService()
    val scanner = ingestService?.ingestScanner
    // A stable fallback StateFlow for the brief window before the service
    // bind callback lands (Android's bindService is async) - collecting a
    // nullable flow directly isn't an option, and this must be `remember`ed
    // rather than built fresh each recomposition (kotlin-flow-state-event-
    // modeling: "a new sharing coroutine every call").
    val fallbackScanFlow = remember { MutableStateFlow<ScanState>(ScanState.Idle) }
    val scanState by (scanner?.state ?: fallbackScanFlow).collectAsStateWithLifecycle()

    val treeUri by LedgerFolderPreferences.treeUri.collectAsStateWithLifecycle()
    var folderUi by remember { mutableStateOf<LedgerFolderUiState>(LedgerFolderUiState.NotConnected) }
    var hasGeminiKey by remember { mutableStateOf(GeminiKeyProvider.hasKey()) }

    suspend fun refreshFolderStatus() {
        val status = LedgerFolderPreferences.connectionStatus(context)
        folderUi = when {
            status.uri == null -> LedgerFolderUiState.NotConnected
            status.permissionGranted -> LedgerFolderUiState.Connected(status.displayName)
            else -> LedgerFolderUiState.PermissionRevoked(status.displayName)
        }
    }

    // Recomputed whenever the connected folder itself changes (connect,
    // change, disconnect)...
    LaunchedEffect(treeUri) { refreshFolderStatus() }
    // ...and on resume, since both signals can go stale for reasons entirely
    // outside this app: a Gemini key saved from `settings/key` (this screen
    // was never told), or a Drive grant revoked by removing the Google
    // account or clearing the Drive app's data while this tab sat in the
    // background. Cheap - one SharedPreferences read and a content-resolver
    // query, not a poll.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { refreshFolderStatus() }
        hasGeminiKey = GeminiKeyProvider.hasKey()
    }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) LedgerFolderPreferences.connect(context, uri)
    }

    LaunchedEffect(reloadNonce) {
        state = state.copy(
            loading = false,
            transactions = LedgerController.recentTransactions(context),
            balances = LedgerController.accountBalances(context),
            quarantined = LedgerController.quarantinedFiles(context),
        )
    }

    // A scan writes straight to Room via IngestScanner/IngestPipeline - this
    // screen otherwise only reads what a PAST scan (or a hand pick) already
    // committed, so a finished scan has to explicitly trigger a reload to
    // show what it just did.
    LaunchedEffect(scanState) {
        if (scanState is ScanState.Finished) reloadNonce++
    }

    // Folder/scan/key are live signals, not part of the async DB load above -
    // merged into a fresh value each recomposition rather than folded back
    // into the remembered `state` var, so this never risks a self-triggered
    // recompose loop over fields that already have their own source of truth.
    val fullState = state.copy(folder = folderUi, scanState = scanState, hasGeminiKey = hasGeminiKey)

    LedgerContent(
        state = fullState,
        onOpenImport = onOpenImport,
        onRetryQuarantine = { driveFileId ->
            scope.launch {
                LedgerController.retryQuarantined(context, driveFileId)
                reloadNonce++
            }
        },
        onConnectFolder = { pickFolder.launch(null) },
        onChangeFolder = { pickFolder.launch(null) },
        onDisconnectFolder = { LedgerFolderPreferences.disconnect(context) },
        onScanNow = {
            val uri = treeUri
            // Handed to the SERVICE's scope, not this composable's - leaving
            // the ledger tab must not cancel a running scan. See
            // LedgerIngestService.startScan.
            if (uri != null) ingestService?.startScan(uri)
        },
        // Never auto-approves (ticket 06 resolution §5) - these only ever
        // fire from the gate card's own buttons, one explicit tap each time
        // ScanState.AwaitingApproval is reached. A null scanner (bind not
        // landed yet) makes this a safe no-op rather than a crash.
        onApproveLlm = { scanner?.approveLlm() },
        onDeclineLlm = { scanner?.declineLlm() },
        onOpenKeySettings = onOpenKeySettings,
    )
}

/**
 * Binds to [LedgerIngestService] for the composable's lifetime and exposes
 * its [IngestScanner], or null before the (async) bind callback lands. See
 * this file's doc comment for why [LedgerIngestService] rather than
 * `AriaForegroundService`. `BIND_AUTO_CREATE` because the ledger tab being
 * open is reason enough for the service to exist - there is no ignition
 * toggle to check first the way there is for the voice assistant.
 */
@Composable
private fun rememberIngestService(): LedgerIngestService? {
    val context = LocalContext.current
    var service by remember { mutableStateOf<LedgerIngestService?>(null) }
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? LedgerIngestService.LocalBinder)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val bound = context.bindService(
            Intent(context, LedgerIngestService::class.java), connection, Context.BIND_AUTO_CREATE,
        )
        onDispose {
            service = null
            if (bound) context.unbindService(connection)
        }
    }
    return service
}

/** Plain UI: [state] plus callbacks, no controller/service reference - see the file doc comment. */
@Composable
fun LedgerContent(
    state: LedgerUiState,
    onOpenImport: () -> Unit,
    onRetryQuarantine: (driveFileId: String) -> Unit,
    onConnectFolder: () -> Unit,
    onChangeFolder: () -> Unit,
    onDisconnectFolder: () -> Unit,
    onScanNow: () -> Unit,
    onApproveLlm: () -> Unit,
    onDeclineLlm: () -> Unit,
    onOpenKeySettings: () -> Unit,
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

            FolderConnectionRow(
                folder = state.folder,
                scanState = state.scanState,
                onConnect = onConnectFolder,
                onChangeFolder = onChangeFolder,
                onDisconnectFolder = onDisconnectFolder,
                onScanNow = onScanNow,
            )
            Hairline()
            ScanStatusSection(
                scanState = state.scanState,
                hasGeminiKey = state.hasGeminiKey,
                onApprove = onApproveLlm,
                onDecline = onDeclineLlm,
                onOpenKeySettings = onOpenKeySettings,
            )

            when {
                state.loading -> Text(
                    "Loading...",
                    style = LegionType.stamp,
                    color = sem.ghost,
                    modifier = Modifier.padding(12.dp),
                )
                // Only rendered while nothing is actively scanning -
                // ScanStatusSection above already communicates progress, and
                // showing a "nothing here" message next to a live progress
                // bar would read as contradictory.
                state.transactions.isEmpty() && state.quarantined.isEmpty() &&
                    (state.scanState is ScanState.Idle || state.scanState is ScanState.Finished) ->
                    LedgerEmptySection(state, onOpenImport, onScanNow)
                else -> LedgerListing(state, onRetryQuarantine)
            }
        }
    }
}

/**
 * Resolves and renders one of ticket 08's three empty-state copies via
 * [LedgerEmptyStateResolver] - Part 5 shipped all three, Part 6 wires the two
 * that need a folder/scan signal to tell apart. [LedgerFolderUiState.PermissionRevoked]
 * is treated as "not connected" here on purpose: [FolderConnectionRow] above
 * already renders the specific revoked-permission explanation and its own
 * RECONNECT action, so this section falls back to the generic no-folder copy
 * rather than repeating that message a second time in different words.
 */
@Composable
private fun LedgerEmptySection(state: LedgerUiState, onOpenImport: () -> Unit, onScanNow: () -> Unit) {
    val kind = LedgerEmptyStateResolver.resolve(
        folderConnected = state.folder is LedgerFolderUiState.Connected,
        lastFinished = (state.scanState as? ScanState.Finished)?.results,
    )
    when (kind) {
        LedgerEmptyStateResolver.Kind.NO_FOLDER -> LedgerEmptyState(
            title = LedgerEmptyCopy.NO_FOLDER_TITLE,
            body = LedgerEmptyCopy.NO_FOLDER_BODY,
            actionLabel = "Import a statement",
            onAction = onOpenImport,
        )
        LedgerEmptyStateResolver.Kind.NOTHING_NEW -> LedgerEmptyState(
            title = LedgerEmptyCopy.NOTHING_NEW_TITLE,
            body = LedgerEmptyCopy.NOTHING_NEW_BODY,
            actionLabel = "Scan again",
            onAction = onScanNow,
        )
        LedgerEmptyStateResolver.Kind.LOOKS_EMPTY -> LedgerEmptyState(
            title = LedgerEmptyCopy.LOOKS_EMPTY_TITLE,
            body = LedgerEmptyCopy.LOOKS_EMPTY_BODY,
            actionLabel = "Scan folder",
            onAction = onScanNow,
        )
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
    LedgerContent(
        LedgerUiState(loading = true),
        onOpenImport = {}, onRetryQuarantine = {}, onConnectFolder = {}, onChangeFolder = {},
        onDisconnectFolder = {}, onScanNow = {}, onApproveLlm = {}, onDeclineLlm = {}, onOpenKeySettings = {},
    )
}

@Preview(name = "Ledger empty: no folder connected", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerEmptyNoFolder() = LegionTheme {
    LedgerContent(
        LedgerUiState(loading = false),
        onOpenImport = {}, onRetryQuarantine = {}, onConnectFolder = {}, onChangeFolder = {},
        onDisconnectFolder = {}, onScanNow = {}, onApproveLlm = {}, onDeclineLlm = {}, onOpenKeySettings = {},
    )
}

@Preview(name = "Ledger empty: connected, nothing new", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerEmptyNothingNew() = LegionTheme {
    LedgerContent(
        LedgerUiState(
            loading = false,
            folder = LedgerFolderUiState.Connected("LegionStatements"),
            scanState = ScanState.Finished(FileResults(skipped = 6)),
        ),
        onOpenImport = {}, onRetryQuarantine = {}, onConnectFolder = {}, onChangeFolder = {},
        onDisconnectFolder = {}, onScanNow = {}, onApproveLlm = {}, onDeclineLlm = {}, onOpenKeySettings = {},
    )
}

@Preview(name = "Ledger empty: connected, folder looks empty", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerEmptyLooksEmpty() = LegionTheme {
    LedgerContent(
        LedgerUiState(
            loading = false,
            folder = LedgerFolderUiState.Connected("LegionStatements"),
            scanState = ScanState.Finished(FileResults()),
        ),
        onOpenImport = {}, onRetryQuarantine = {}, onConnectFolder = {}, onChangeFolder = {},
        onDisconnectFolder = {}, onScanNow = {}, onApproveLlm = {}, onDeclineLlm = {}, onOpenKeySettings = {},
    )
}

@Preview(name = "Ledger: awaiting the spend gate", widthDp = 360, heightDp = 720)
@Composable
private fun PreviewLedgerAwaitingApproval() = LegionTheme {
    LedgerContent(
        LedgerUiState(
            loading = false,
            folder = LedgerFolderUiState.Connected("LegionStatements"),
            scanState = ScanState.AwaitingApproval(
                newFiles = 14,
                estimate = SpendEstimate(14, 3_220, 915, basedOnMeasuredAverage = false),
            ),
        ),
        onOpenImport = {}, onRetryQuarantine = {}, onConnectFolder = {}, onChangeFolder = {},
        onDisconnectFolder = {}, onScanNow = {}, onApproveLlm = {}, onDeclineLlm = {}, onOpenKeySettings = {},
    )
}

@Preview(name = "Ledger: balances + quarantine + stream", widthDp = 360, heightDp = 900)
@Composable
private fun PreviewLedgerPopulated() = LegionTheme {
    LedgerContent(
        state = LedgerUiState(
            loading = false,
            folder = LedgerFolderUiState.Connected("LegionStatements"),
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
        onOpenImport = {}, onRetryQuarantine = {}, onConnectFolder = {}, onChangeFolder = {},
        onDisconnectFolder = {}, onScanNow = {}, onApproveLlm = {}, onDeclineLlm = {}, onOpenKeySettings = {},
    )
}
