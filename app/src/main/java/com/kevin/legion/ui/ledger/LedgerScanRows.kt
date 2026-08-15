package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.FileResults
import com.kevin.legion.service.ScanState
import com.kevin.legion.service.SpendEstimate
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Ticket 08 Part 6's read-and-drive surfaces: folder connection, scan
 * progress against [ScanState]'s contract, and the ticket 06 spend gate. Same
 * split as `LedgerRows.kt` (Part 4-7 read surfaces) - everything here is
 * display plus callbacks, no [com.kevin.legion.ledger.LedgerFolderPreferences]
 * or [com.kevin.legion.service.IngestScanner] reference. `ui/LedgerScreen.kt`
 * is the state holder that owns those.
 *
 * **Provisional, same status as the Part 5 rows** (ticket 08 resolution
 * "1, 2, 3, 6, 7 - built, NOT visually reviewed"): single takes exist here,
 * previewed in Studio, never rendered on the A17K.
 */

// ------------------------------------------------------------- folder connection (item 1)

/** Presentation state for the connected-folder row. [LedgerFolderUiState.Connected]/[PermissionRevoked]'s [displayName] is best-effort, never used as identity - see [com.kevin.legion.ledger.LedgerFolderPreferences]. */
sealed interface LedgerFolderUiState {
    data object NotConnected : LedgerFolderUiState
    data class Connected(val displayName: String) : LedgerFolderUiState
    /** The persisted grant is gone (account removed, Drive data cleared, or the OS trimmed it) - resolution's "include the revoked-permission state". */
    data class PermissionRevoked(val displayName: String) : LedgerFolderUiState
}

/**
 * Folder connection (resolution item 1): which folder is connected (or that
 * none is), and the actions to connect / change / disconnect it. Also owns
 * the "scan now" trigger - it lives here rather than in [ScanStatusSection]
 * because whether a scan can even be offered is a direct function of
 * connection state, and [scanState] is only consulted to disable the button
 * mid-scan rather than showing it twice.
 */
@Composable
fun FolderConnectionRow(
    folder: LedgerFolderUiState,
    scanState: ScanState,
    onConnect: () -> Unit,
    onChangeFolder: () -> Unit,
    onDisconnectFolder: () -> Unit,
    onScanNow: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val scanning = scanState !is ScanState.Idle && scanState !is ScanState.Finished

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (folder) {
            is LedgerFolderUiState.NotConnected -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("No statements folder connected", style = MaterialTheme.typography.bodyMedium, color = sem.faint)
                TextButton(onClick = onConnect) {
                    Text("CONNECT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }

            is LedgerFolderUiState.Connected -> Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Connected folder", style = LegionType.stamp, color = sem.faint)
                    Text(folder.displayName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                Row {
                    TextButton(onClick = onScanNow, enabled = !scanning) {
                        Text(if (scanning) "SCANNING" else "SCAN", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onChangeFolder, enabled = !scanning) {
                        Text("CHANGE", style = LegionType.stamp, color = sem.faint)
                    }
                    TextButton(onClick = onDisconnectFolder, enabled = !scanning) {
                        Text("DISCONNECT", style = LegionType.stamp, color = sem.faint)
                    }
                }
            }

            is LedgerFolderUiState.PermissionRevoked -> Column {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
                        Text("Folder permission was revoked", style = MaterialTheme.typography.bodyMedium, color = sem.estimated)
                        Text(folder.displayName, style = LegionType.stamp, color = sem.faint)
                    }
                    TextButton(onClick = onConnect) {
                        Text("RECONNECT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Resolution: "states plainly that nothing already imported is
                // affected" - a revoked grant only stops FUTURE scans, it
                // cannot and does not touch a row already committed.
                Text(
                    "Nothing already imported is affected. This only stops future scans of this folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                )
            }
        }
    }
}

// ------------------------------------------------------------- scan progress (item 2)

/**
 * Renders the live [ScanState] (resolution item 2). One `when` arm per
 * contract state, per [ScanState]'s own doc comment - [ScanState.Idle] draws
 * nothing here, since [FolderConnectionRow]'s own SCAN button already covers
 * that state and a second "nothing is happening" line would be noise.
 */
@Composable
fun ScanStatusSection(
    scanState: ScanState,
    hasGeminiKey: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onOpenKeySettings: () -> Unit,
) {
    when (scanState) {
        ScanState.Idle -> Unit
        is ScanState.Listing -> ScanProgressLine(
            "Listing the folder" + if (scanState.folderCount > 0) " (${scanState.folderCount} found)" else "",
        )
        is ScanState.Staging -> ScanProgressBar("Checking files", scanState.done, scanState.total)
        is ScanState.ParsingDeterministic -> ScanProgressBar(
            "Reading statements", scanState.done, scanState.total, scanState.currentName,
        )
        is ScanState.AwaitingApproval -> SpendGateCard(
            newFiles = scanState.newFiles,
            estimate = scanState.estimate,
            hasGeminiKey = hasGeminiKey,
            onApprove = onApprove,
            onDecline = onDecline,
            onOpenKeySettings = onOpenKeySettings,
        )
        is ScanState.ParsingLlm -> ScanProgressBar(
            "Reading with AI", scanState.done, scanState.total, scanState.currentName,
        )
        is ScanState.Finished -> ScanFinishedLine(scanState.results)
    }
}

/** A progress state with no natural done/total (listing hasn't produced a count worth a bar over yet). */
@Composable
private fun ScanProgressLine(label: String) {
    val sem = LocalLegionSemantics.current
    Text(label, style = MaterialTheme.typography.bodyMedium, color = sem.faint, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
}

/** A determinate `done`/`total` phase (Staging, ParsingDeterministic, ParsingLlm) - both parse phases get their OWN bar (ticket 06 amendment), never merged into one, since they have very different costs and the driver should see which is running. */
@Composable
private fun ScanProgressBar(label: String, done: Int, total: Int, currentName: String? = null) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("$done / $total", style = LegionType.stamp, color = sem.faint)
        }
        if (!currentName.isNullOrBlank()) {
            Text(currentName, style = LegionType.stamp, color = sem.faint)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A brief one-line summary once [ScanState.Finished] lands - the real content (new transactions/quarantine rows) speaks for itself below; this line just confirms what happened for a scan that changed nothing visible. */
@Composable
private fun ScanFinishedLine(results: FileResults) {
    val sem = LocalLegionSemantics.current
    val parts = buildList {
        if (results.ingested > 0) add("${results.ingested} imported")
        if (results.quarantined > 0) add("${results.quarantined} quarantined")
        if (results.needsLlmDeclined > 0) add("${results.needsLlmDeclined} declined, offered again next scan")
        if (results.unreadable > 0) add("${results.unreadable} unreadable")
    }
    // "Nothing new" still gets a line. The empty-state copy below only renders
    // when the ledger has NO content at all, so on a ledger that already holds
    // transactions an early return here left a finished scan with no feedback
    // whatsoever - SCAN looked broken. Verified on the A17K 2026-08-02.
    //
    // Deliberately "nothing new", not "the folder is empty": ticket 05 §9 is
    // explicit that a scan legitimately finding nothing is a normal outcome and
    // must never be surfaced as emptiness or as an error. Duplicates and skips
    // are folded into it rather than counted out - "3 already on file" invites
    // the driver to wonder what went wrong when the honest answer is nothing.
    Text(
        if (parts.isEmpty()) "Scan finished: nothing new." else "Scan finished: ${parts.joinToString(", ")}.",
        style = LegionType.stamp,
        color = sem.faint,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// ------------------------------------------------------------- the spend gate (item 3)

/**
 * Ticket 06's gate, followed exactly: count leads (it is EXACT - deterministic
 * parsing never touches Gemini, so [newFiles] costs nothing to know before
 * asking), cost is a labelled SECONDARY estimate in [com.kevin.legion.ui.theme.LegionSemantics.estimated]
 * (CLAUDE.md §4 rule 5 - a cost projection is unstated by any source document,
 * so it must read as an estimate, never a fact). **No price is rendered** -
 * [SpendEstimate] deliberately carries none (ticket 06 §2 found no verified
 * current price and explicitly refused to ship a stale one) - only the token
 * counts the estimate actually holds. **Never auto-approves**: this card is
 * the only path to [onApprove]/[onDecline], and it renders every single time
 * [ScanState.AwaitingApproval] is reached (ticket 06 resolution §5 - "ask
 * every time, never remember, at any scope").
 */
@Composable
fun SpendGateCard(
    newFiles: Int,
    estimate: SpendEstimate,
    hasGeminiKey: Boolean,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onOpenKeySettings: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (newFiles == 1) "1 statement needs AI reading" else "$newFiles statements need AI reading",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text("This uses your own Gemini key.", style = LegionType.stamp, color = sem.faint)

            Spacer(Modifier.height(8.dp))

            val totalPromptTokens = estimate.estimatedPromptTokensPerFile.toLong() * estimate.fileCount
            val totalResponseTokens = estimate.estimatedResponseTokensPerFile.toLong() * estimate.fileCount
            Text(
                "~${"%,d".format(totalPromptTokens)} prompt + ~${"%,d".format(totalResponseTokens)} response tokens",
                style = LegionType.reading,
                color = sem.estimated,
            )
            Text(
                if (estimate.basedOnMeasuredAverage) {
                    "Estimate only, based on your own past usage measured on this device. No price is " +
                        "shown - it hasn't been verified. Actual usage is billed by Google, not by LEGION."
                } else {
                    "Estimate only - a reasoned guess, nothing has been measured on this device yet. No " +
                        "price is shown - it hasn't been verified. Actual usage is billed by Google, not by LEGION."
                },
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
            )

            Spacer(Modifier.height(10.dp))

            if (!hasGeminiKey) {
                // Resolution: "the gate must route to the existing settings/key
                // screen rather than failing silently or spending nothing with
                // no explanation." Decline is still always available - the
                // rule is "ask every time", not "block until a key exists".
                Text(
                    "Reading with AI needs a Gemini key, which isn't set up yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDecline) { Text("NOT NOW", style = LegionType.stamp, color = sem.faint) }
                    TextButton(onClick = onOpenKeySettings) {
                        Text("ADD A GEMINI KEY", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDecline) { Text("NOT NOW", style = LegionType.stamp, color = sem.faint) }
                    TextButton(onClick = onApprove) { Text("READ THEM", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Folder: not connected", widthDp = 360)
@Composable
private fun PreviewFolderNotConnected() = LegionTheme {
    Surface { FolderConnectionRow(LedgerFolderUiState.NotConnected, ScanState.Idle, {}, {}, {}, {}) }
}

@Preview(name = "Folder: connected, idle", widthDp = 360)
@Composable
private fun PreviewFolderConnected() = LegionTheme {
    Surface { FolderConnectionRow(LedgerFolderUiState.Connected("LegionStatements"), ScanState.Idle, {}, {}, {}, {}) }
}

@Preview(name = "Folder: connected, scan running (actions disabled)", widthDp = 360)
@Composable
private fun PreviewFolderConnectedScanning() = LegionTheme {
    Surface { FolderConnectionRow(LedgerFolderUiState.Connected("LegionStatements"), ScanState.Listing(5), {}, {}, {}, {}) }
}

@Preview(name = "Folder: permission revoked", widthDp = 360)
@Composable
private fun PreviewFolderRevoked() = LegionTheme {
    Surface { FolderConnectionRow(LedgerFolderUiState.PermissionRevoked("LegionStatements"), ScanState.Idle, {}, {}, {}, {}) }
}

@Preview(name = "Scan: listing", widthDp = 360)
@Composable
private fun PreviewScanListing() = LegionTheme {
    Surface { ScanStatusSection(ScanState.Listing(23), hasGeminiKey = true, {}, {}, {}) }
}

@Preview(name = "Scan: deterministic parse in progress", widthDp = 360)
@Composable
private fun PreviewScanDeterministic() = LegionTheme {
    Surface {
        ScanStatusSection(
            ScanState.ParsingDeterministic(7, 23, "eStmt_2026-06.pdf"),
            hasGeminiKey = true, {}, {}, {},
        )
    }
}

@Preview(name = "Gate: has a key", widthDp = 360)
@Composable
private fun PreviewGateHasKey() = LegionTheme {
    Surface {
        SpendGateCard(
            newFiles = 14,
            estimate = SpendEstimate(14, 3_220, 915, basedOnMeasuredAverage = false),
            hasGeminiKey = true,
            onApprove = {}, onDecline = {}, onOpenKeySettings = {},
        )
    }
}

@Preview(name = "Gate: measured average, no key yet", widthDp = 360)
@Composable
private fun PreviewGateNoKey() = LegionTheme {
    Surface {
        SpendGateCard(
            newFiles = 3,
            estimate = SpendEstimate(3, 2_980, 840, basedOnMeasuredAverage = true),
            hasGeminiKey = false,
            onApprove = {}, onDecline = {}, onOpenKeySettings = {},
        )
    }
}

@Preview(name = "Scan: finished, mixed outcome", widthDp = 360)
@Composable
private fun PreviewScanFinished() = LegionTheme {
    Surface {
        ScanStatusSection(
            ScanState.Finished(FileResults(ingested = 11, quarantined = 1, unreadable = 1)),
            hasGeminiKey = true, {}, {}, {},
        )
    }
}
