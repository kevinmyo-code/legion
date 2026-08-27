package com.kevin.legion.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ledger.IngestPipeline
import com.kevin.legion.ledger.ReingestDryRun
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `settings/ledger-reingest-dry-run` - the hands path for [ReingestDryRun], ticket 12's "check the
 * Drive folder first" step (`.scratch/backend-erp/issues/12-ledger-rows-have-no-statement-header.md`).
 * Reached from [ConnectionsScreen], same shape as `settings/backend-migration`.
 *
 * **This screen writes nothing, ever - the one thing it must never be mistaken for is the real
 * re-ingest.** [ReingestDryRun.run] takes no [Context] and touches no DAO by construction (see
 * that object's own class doc, and `ReingestDryRunTest`'s structural guard), so there is nothing
 * for this screen to accidentally wire into a write path even if it tried; the header text below
 * says so anyway, because a screen that merely happens to be safe is not the same as a screen that
 * SAYS it is safe (CLAUDE.md's own "unreadable and empty are different sentences" posture, applied
 * here to "read-only" versus "looks read-only").
 *
 * Same state-holder/content split as [BackendMigrationScreen]: this function owns
 * [CarDatabase]/SAF I/O/coroutine plumbing; [ReingestDryRunContent] is plain state-plus-callbacks;
 * [ReingestDryRunResolver] is the pure report-to-words layer both this file and its test target.
 */
@Composable
fun ReingestDryRunScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf(ReingestDryRunUiState()) }

    fun run() {
        uiState = uiState.copy(running = true, summaryLines = null, fileLines = null, failure = null)
        scope.launch {
            try {
                val inputs = withContext(Dispatchers.IO) {
                    CarDatabase.getDatabase(context).ingestedFileDao().listIngestedWithTreeUri()
                        .mapNotNull { file ->
                            val treeUri = file.treeUri ?: return@mapNotNull null
                            ReingestDryRun.FileInput(file.driveFileId, treeUri, file.displayName)
                        }
                }
                val reports = ReingestDryRun.run(inputs, safReader(context))
                val aggregate = ReingestDryRun.aggregate(reports)
                uiState = uiState.copy(
                    running = false,
                    summaryLines = ReingestDryRunResolver.renderSummary(aggregate),
                    fileLines = reports.map { ReingestDryRunResolver.renderFileLine(it) },
                )
            } catch (e: Exception) {
                Log.w("ReingestDryRunScreen", "dry run failed: ${e.message}")
                uiState = uiState.copy(running = false, failure = "Didn't finish - ${e.message ?: "unknown error"}. Nothing was written either way.")
            }
        }
    }

    ReingestDryRunContent(state = uiState, onRun = ::run, onBack = onBack)
}

/**
 * SAF byte reader for [ReingestDryRun.ByteReader] - the exact "open by tree + document id, null
 * on any failure, never throw" contract [com.kevin.legion.service.IngestScanner]'s own private
 * `openBytes` uses, so this reuses that shape rather than inventing a second one.
 *
 * **`driveFileId` is a stored KEY, not the ADDRESS [DocumentsContract] needs** -
 * [IngestPipeline.stripAccountPrefix] removed its `acc=N;` prefix before it was ever written to
 * `ingested_files`. [IngestPipeline.reattachAccountPrefix] puts it back on, derived from THIS
 * tree's own document id, before the URI is built - see that function's own doc for why a live
 * scan never hits this (it opens bytes with the file's original unstripped id and only stores the
 * stripped copy) and this dry run does. Skipping this step is exactly what made every one of 107
 * real files on-device read as UNREACHABLE against a valid, persisted SAF grant.
 */
private fun safReader(context: Context) = ReingestDryRun.ByteReader { driveFileId, treeUri, _ ->
    withContext(Dispatchers.IO) {
        try {
            val tree = Uri.parse(treeUri)
            val fullDocumentId = IngestPipeline.reattachAccountPrefix(
                driveFileId,
                DocumentsContract.getTreeDocumentId(tree),
            )
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, fullDocumentId)
            context.contentResolver.openInputStream(docUri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w("ReingestDryRunScreen", "read failed for $driveFileId: ${e.message}")
            null
        }
    }
}

/** Transient screen state - never persisted, matches [ReconcileRowUiState]'s own shape. */
data class ReingestDryRunUiState(
    val running: Boolean = false,
    val summaryLines: List<String>? = null,
    val fileLines: List<String>? = null,
    val failure: String? = null,
)

/** Plain state-plus-callbacks content - previewable with no Android services. */
@Composable
fun ReingestDryRunContent(
    state: ReingestDryRunUiState,
    onRun: () -> Unit,
    onBack: () -> Unit,
) {
    val sem = LocalLegionSemantics.current

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Ledger re-ingest dry run", onBack = onBack)
            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "READ-ONLY. Re-reads every statement previously ingested from a connected " +
                        "Drive folder and re-parses it with the same deterministic parsers, to " +
                        "check whether the printed total, opening balance, and closing balance " +
                        "could be recovered for a future upload. Nothing is written - not a row, " +
                        "not a state change, nothing in the ledger changes because you ran this.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(onClick = onRun, enabled = !state.running) {
                        Text(
                            if (state.running) "RUNNING (READ-ONLY)" else "RUN DRY RUN",
                            style = LegionType.stamp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                state.failure?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = sem.quarantined, modifier = Modifier.padding(horizontal = 12.dp))
                }

                state.summaryLines?.let { lines ->
                    Spacer(Modifier.height(16.dp))
                    for (line in lines) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                }

                state.fileLines?.let { lines ->
                    Spacer(Modifier.height(16.dp))
                    Text("Per file:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
                    for (line in lines) {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = sem.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReingestDryRunContentIdlePreview() {
    LegionTheme {
        ReingestDryRunContent(state = ReingestDryRunUiState(), onRun = {}, onBack = {})
    }
}

@Preview(showBackground = true)
@Composable
private fun ReingestDryRunContentResultPreview() {
    LegionTheme {
        ReingestDryRunContent(
            state = ReingestDryRunUiState(
                summaryLines = listOf(
                    "Read-only dry run over 107 statement files previously ingested from a connected Drive folder. Nothing was written.",
                    "62 would recover all three anchors (opening balance, closing balance, stated total) and unblock ticket 12 for that file.",
                    "40 parsed but recovered fewer than three anchors - a rule-7 provisional candidate each, not a failure. Missing: 40 files missing the opening balance; 40 files missing the closing balance.",
                    "3 files no longer reachable through its saved folder link.",
                    "2 files quarantined on re-read - the numbers no longer reconcile.",
                    "Raw rows re-parsed: 1220. PROJECTED row count after replaying dedup in memory: 168. This is a projection, not a promise.",
                ),
                fileLines = listOf(
                    "jan_statement.pdf: PARSED, all 3 anchors recovered - 22 rows parsed. Opening \$1,200.00, closing \$980.50, total -\$219.50.",
                    "card_export.csv: PARSED, provisional candidate - 14 rows parsed. Missing: opening balance, closing balance.",
                ),
            ),
            onRun = {},
            onBack = {},
        )
    }
}
