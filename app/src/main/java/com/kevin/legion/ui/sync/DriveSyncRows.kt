package com.kevin.legion.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.sync.DatabaseSnapshot
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Plain UI half of `ui/DriveSyncScreen.kt` (the state-holder/UI split,
 * `.claude/skills/compose-state-holder-ui-split`, same shape as
 * `LedgerScanRows.kt`'s [com.kevin.legion.ui.ledger.FolderConnectionRow]).
 * Everything here is display plus callbacks, driven by
 * [GoogleGrantResolver.Availability] rather than owning
 * [com.kevin.legion.sync.DriveAuth]/[com.kevin.legion.sync.SyncCapability]
 * itself, so it previews without a `Context`.
 *
 * **Provisional, same status as the ledger Part 5/6 rows** - single takes
 * here, previewed in Studio, not yet rendered on the A17K.
 */

/**
 * Connection status plus the connect/disconnect action (resolution item 1's
 * analogue for Drive rather than the ledger folder). [working] disables the
 * button mid-round-trip so a second tap can't race the first while
 * `DriveAuth.authorize` or the consent Activity is in flight.
 */
@Composable
fun DriveConnectionRow(
    availability: GoogleGrantResolver.Availability,
    working: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        when (availability) {
            GoogleGrantResolver.Availability.UNAVAILABLE -> {
                // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
                Text(
                    GoogleGrantResolver.unavailableMessage(GoogleGrantResolver.Grant.DRIVE),
                    style = MaterialTheme.typography.bodyMedium,
                    color = sem.estimated,
                )
            }

            GoogleGrantResolver.Availability.DISCONNECTED -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Google Drive", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "Not connected. Ledger and pantry data stay on this device only.",
                            style = LegionType.stamp,
                            color = sem.faint,
                        )
                    }
                    TextButton(onClick = onConnect, enabled = !working) {
                        Text(if (working) "CONNECTING" else "CONNECT", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            GoogleGrantResolver.Availability.CONNECTED -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Google Drive", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text("Connected", style = LegionType.stamp, color = sem.faint)
                    }
                    TextButton(onClick = onDisconnect, enabled = !working) {
                        Text("DISCONNECT", style = LegionType.stamp, color = sem.faint)
                    }
                }
            }
        }
    }
}

/**
 * The manual "Sync now" trigger plus its result (resolution's whole point
 * for a first run: a deliberate pass, not waiting on
 * [com.kevin.legion.sync.SyncEngine.maybeAutoSync]'s 5-minute throttle).
 * [ok] is null before any pass has been run this screen-visit.
 */
@Composable
fun SyncNowRow(
    canSync: Boolean,
    working: Boolean,
    ok: Boolean?,
    message: String?,
    onSyncNow: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sync now", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                TextButton(onClick = onSyncNow, enabled = canSync && !working) {
                    Text(if (working) "SYNCING" else "SYNC NOW", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!canSync) {
                Spacer(Modifier.height(4.dp))
                Text("Connect Google Drive first.", style = LegionType.stamp, color = sem.faint)
            }
            if (message != null) {
                Spacer(Modifier.height(6.dp))
                // ADVISORY (ticket 13 re-home): a sync result, not a failed gate.
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok == false) sem.estimated else sem.faint,
                )
            }
        }
    }
}

/**
 * "Whole-database backup" panel (Phase 0, [com.kevin.legion.sync.DatabaseSnapshot]) - the
 * "BACK UP NOW" action plus the last-backup summary. Deliberately a SEPARATE panel from
 * [SyncNowRow] above, not folded into it: this is [DatabaseSnapshot]'s independent
 * whole-file snapshot, not [com.kevin.legion.sync.SyncEngine]'s row-level table merge, and
 * the two must never read as the same action to the driver.
 */
@Composable
fun DatabaseBackupRow(
    canBackUp: Boolean,
    working: Boolean,
    summary: String,
    resultMessage: String?,
    resultIsError: Boolean,
    onBackUpNow: () -> Unit,
    /** Non-null only right after [DatabaseSnapshotGuard] refused a routine backup - a
     * distinct, deliberate escape hatch (Ravi's review, ALSO-FIX 5), never shown as part of
     * the normal flow. Invoked with the exact refusal reason so the caller can open a
     * SEPARATE, worded confirm dialog before actually overriding anything. */
    onBackUpAnywayRequested: (() -> Unit)? = null,
) {
    val sem = LocalLegionSemantics.current
    Surface(Modifier.fillMaxWidth(), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Whole-database backup", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(summary, style = LegionType.stamp, color = sem.faint)
                }
                TextButton(onClick = onBackUpNow, enabled = canBackUp && !working) {
                    Text(if (working) "BACKING UP" else "BACK UP NOW", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
            // Ticket 09's ruling: database-only, and it must SAY so, on the same panel that
            // offers the button - not buried in a confirm dialog someone only sees on restore.
            Spacer(Modifier.height(2.dp))
            Text(DriveBackupResolver.PHOTO_COVERAGE_CAVEAT, style = LegionType.stamp, color = sem.faint)
            if (!canBackUp) {
                Spacer(Modifier.height(4.dp))
                Text("Connect Google Drive first.", style = LegionType.stamp, color = sem.faint)
            }
            if (resultMessage != null) {
                Spacer(Modifier.height(6.dp))
                // ADVISORY (ticket 13 re-home): a backup result, not a failed gate.
                Text(
                    resultMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (resultIsError) sem.estimated else sem.faint,
                )
            }
            if (onBackUpAnywayRequested != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onBackUpAnywayRequested, enabled = canBackUp && !working) {
                    // ADVISORY (ticket 13 re-home): overriding a blocked backup guard, not a
                    // destructive action - nothing is deleted by this button.
                    Text("BACK UP ANYWAY", style = LegionType.stamp, color = sem.estimated)
                }
            }
        }
    }
}

/**
 * One row in the "generations available" list ([DriveBackupResolver.GenerationRow]) - a
 * timestamp, a row count, a NEWEST marker on the first row, and a RESTORE action that is
 * disabled (with a worded reason, CLAUDE.md §7 - never colour-only) when the generation's
 * schema is newer than this build's.
 */
@Composable
fun BackupGenerationRow(row: DriveBackupResolver.GenerationRow, onRestore: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (row.isNewest) "${row.timeLabel} (newest)" else row.timeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(row.rowCountLabel, style = LegionType.stamp, color = sem.faint)
            row.disabledReason?.let {
                // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
                Text(it, style = LegionType.stamp, color = sem.estimated)
            }
        }
        TextButton(onClick = onRestore, enabled = row.canRestore) {
            Text("RESTORE", style = LegionType.stamp, color = if (row.canRestore) MaterialTheme.colorScheme.primary else sem.faint)
        }
    }
}

/**
 * The deliberate-confirm dialog a restore requires (task brief: "requires a deliberate
 * confirm... worded, never colour-only"). [message] comes from
 * [DriveBackupResolver.confirmRestoreMessage] (a Drive generation) or
 * [DriveBackupResolver.confirmLocalRecoveryMessage] (a local recovery point) - either names
 * the specific timestamp/row count plus the restart requirement, nothing here is a generic
 * "are you sure?". [title]/[confirmLabel] default to the Drive-restore wording; local
 * recovery passes its own.
 */
@Composable
fun RestoreConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    title: String = "Restore this backup?",
    confirmLabel: String = "Restore",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One row in the "Recover locally" list ([DriveBackupResolver.LocalRecoveryRow]) - no Drive,
 * no network, tap to restore a pre-restore safety copy or an interrupted restore's original
 * (Ravi's review, ALSO-FIX 7 - replaces a raw file-path error string with something the
 * driver can actually act on).
 */
@Composable
fun LocalRecoveryRow(row: DriveBackupResolver.LocalRecoveryRow, onRestore: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.timeLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(row.kindLabel, style = LegionType.stamp, color = sem.faint)
        }
        TextButton(onClick = onRestore) {
            Text("RESTORE", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ------------------------------------------------------------------------ previews

@Preview(name = "Drive: unavailable (no Play Services)", widthDp = 360)
@Composable
private fun PreviewUnavailable() = LegionTheme {
    Surface { DriveConnectionRow(GoogleGrantResolver.Availability.UNAVAILABLE, working = false, onConnect = {}, onDisconnect = {}) }
}

@Preview(name = "Drive: disconnected", widthDp = 360)
@Composable
private fun PreviewDisconnected() = LegionTheme {
    Surface { DriveConnectionRow(GoogleGrantResolver.Availability.DISCONNECTED, working = false, onConnect = {}, onDisconnect = {}) }
}

@Preview(name = "Drive: connecting", widthDp = 360)
@Composable
private fun PreviewConnecting() = LegionTheme {
    Surface { DriveConnectionRow(GoogleGrantResolver.Availability.DISCONNECTED, working = true, onConnect = {}, onDisconnect = {}) }
}

@Preview(name = "Drive: connected", widthDp = 360)
@Composable
private fun PreviewConnected() = LegionTheme {
    Surface { DriveConnectionRow(GoogleGrantResolver.Availability.CONNECTED, working = false, onConnect = {}, onDisconnect = {}) }
}

@Preview(name = "Sync now: not connected yet", widthDp = 360)
@Composable
private fun PreviewSyncNowDisabled() = LegionTheme {
    Surface { SyncNowRow(canSync = false, working = false, ok = null, message = null, onSyncNow = {}) }
}

@Preview(name = "Sync now: success", widthDp = 360)
@Composable
private fun PreviewSyncNowSuccess() = LegionTheme {
    Surface { SyncNowRow(canSync = true, working = false, ok = true, message = "Synced with your Google Drive.", onSyncNow = {}) }
}

@Preview(name = "Sync now: failure", widthDp = 360)
@Composable
private fun PreviewSyncNowFailure() = LegionTheme {
    Surface { SyncNowRow(canSync = true, working = false, ok = false, message = "Couldn't reach your Google Drive - try again.", onSyncNow = {}) }
}

private fun previewGeneration(ts: Long, schema: Int, rows: Long) =
    DatabaseSnapshot.Generation(timestampMs = ts, schemaVersion = schema, rowCount = rows, dbFileId = "db$ts", metaFileId = "meta$ts")

@Preview(name = "Backup: no backups yet", widthDp = 360)
@Composable
private fun PreviewBackupNone() = LegionTheme {
    Surface { DatabaseBackupRow(canBackUp = true, working = false, summary = "No backups yet.", resultMessage = null, resultIsError = false, onBackUpNow = {}) }
}

@Preview(name = "Backup: working", widthDp = 360)
@Composable
private fun PreviewBackupWorking() = LegionTheme {
    Surface { DatabaseBackupRow(canBackUp = true, working = true, summary = "Last backup: Aug 12, 2026 - 48213 rows.", resultMessage = null, resultIsError = false, onBackUpNow = {}) }
}

@Preview(name = "Backup: success", widthDp = 360)
@Composable
private fun PreviewBackupSuccess() = LegionTheme {
    Surface {
        DatabaseBackupRow(
            canBackUp = true, working = false, summary = "Last backup: Aug 12, 2026 - 48213 rows.",
            resultMessage = "Backed up.", resultIsError = false, onBackUpNow = {},
        )
    }
}

@Preview(name = "Backup: refused (looks like data loss)", widthDp = 360)
@Composable
private fun PreviewBackupRefused() = LegionTheme {
    Surface {
        DatabaseBackupRow(
            canBackUp = true, working = false, summary = "Last backup: Aug 12, 2026 - 48213 rows.",
            resultMessage = "New backup has 0 row(s) vs the last good backup's 48213 - that looks like data loss, " +
                "not a real change. Skipped the upload; your last good backup on Drive is untouched.",
            resultIsError = true, onBackUpNow = {},
        )
    }
}

@Preview(name = "Backup generation: newest, restorable", widthDp = 360)
@Composable
private fun PreviewGenerationNewest() = LegionTheme {
    Surface {
        BackupGenerationRow(
            row = DriveBackupResolver.GenerationRow(
                generation = previewGeneration(3, 15, 48213), timeLabel = "Aug 12, 2026 - 21:04",
                rowCountLabel = "48213 rows", isNewest = true, canRestore = true, disabledReason = null,
            ),
            onRestore = {},
        )
    }
}

@Preview(name = "Backup generation: older, restorable", widthDp = 360)
@Composable
private fun PreviewGenerationOlder() = LegionTheme {
    Surface {
        BackupGenerationRow(
            row = DriveBackupResolver.GenerationRow(
                generation = previewGeneration(2, 15, 47990), timeLabel = "Aug 11, 2026 - 21:04",
                rowCountLabel = "47990 rows", isNewest = false, canRestore = true, disabledReason = null,
            ),
            onRestore = {},
        )
    }
}

@Preview(name = "Backup generation: newer schema, blocked", widthDp = 360)
@Composable
private fun PreviewGenerationBlocked() = LegionTheme {
    Surface {
        BackupGenerationRow(
            row = DriveBackupResolver.GenerationRow(
                generation = previewGeneration(1, 17, 50000), timeLabel = "Aug 13, 2026 - 09:00",
                rowCountLabel = "50000 rows", isNewest = true, canRestore = false,
                disabledReason = "From a newer app version (schema v17) than this one (v15) - update the app before restoring it.",
            ),
            onRestore = {},
        )
    }
}

@Preview(name = "Restore confirm dialog", widthDp = 360)
@Composable
private fun PreviewRestoreConfirmDialog() = LegionTheme {
    RestoreConfirmDialog(
        message = "This replaces everything on this device - ledger, pantry, workouts, lists, garage, all of it - " +
            "with the backup from Aug 12, 2026 - 21:04 (48213 rows). Your current data is saved locally first, so " +
            "this can be undone, but the app must restart to finish. Nothing does this automatically or as part " +
            "of a sync - you have to choose it here.",
        onConfirm = {},
        onDismiss = {},
    )
}

@Preview(name = "Backup: refused, with override action", widthDp = 360)
@Composable
private fun PreviewBackupRefusedWithOverride() = LegionTheme {
    Surface {
        DatabaseBackupRow(
            canBackUp = true, working = false, summary = "Last backup: Aug 12, 2026 - 48213 rows.",
            resultMessage = "New backup has 0 row(s) vs the last good backup's 48213 - that looks like data loss, " +
                "not a real change. Skipped the upload; your last good backup on Drive is untouched.",
            resultIsError = true, onBackUpNow = {}, onBackUpAnywayRequested = {},
        )
    }
}

@Preview(name = "Local recovery: safety copy", widthDp = 360)
@Composable
private fun PreviewLocalRecoverySafetyCopy() = LegionTheme {
    Surface {
        LocalRecoveryRow(
            row = DriveBackupResolver.LocalRecoveryRow(
                recovery = DatabaseSnapshot.LocalRecovery(
                    file = java.io.File("preview.db"), timestampMs = 1L,
                    kind = DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY, label = "",
                ),
                timeLabel = "Aug 13, 2026 - 09:12", kindLabel = "Safety copy",
            ),
            onRestore = {},
        )
    }
}

@Preview(name = "Local recovery: interrupted restore", widthDp = 360)
@Composable
private fun PreviewLocalRecoveryInterrupted() = LegionTheme {
    Surface {
        LocalRecoveryRow(
            row = DriveBackupResolver.LocalRecoveryRow(
                recovery = DatabaseSnapshot.LocalRecovery(
                    file = java.io.File("preview.db"), timestampMs = 1L,
                    kind = DatabaseSnapshot.LocalRecoveryKind.INTERRUPTED_ORIGINAL, label = "",
                ),
                timeLabel = "Aug 13, 2026 - 09:14", kindLabel = "Interrupted restore - your original database",
            ),
            onRestore = {},
        )
    }
}

@Preview(name = "Back up anyway confirm dialog", widthDp = 360)
@Composable
private fun PreviewOverrideGuardDialog() = LegionTheme {
    RestoreConfirmDialog(
        message = "New backup has 0 row(s) vs the last good backup's 48213 - that looks like data loss, not a " +
            "real change.\n\nOnly do this if you deliberately deleted a lot of data yourself. This will " +
            "overwrite the last good backup on Drive with this smaller one.",
        onConfirm = {},
        onDismiss = {},
        title = "Back up anyway?",
        confirmLabel = "Back up anyway",
    )
}
