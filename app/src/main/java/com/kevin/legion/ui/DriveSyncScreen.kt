package com.kevin.legion.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.sync.DatabaseSnapshot
import com.kevin.legion.sync.DriveAuth
import com.kevin.legion.sync.SyncCapability
import com.kevin.legion.sync.SyncEngine
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.sync.BackupGenerationRow
import com.kevin.legion.ui.sync.DatabaseBackupRow
import com.kevin.legion.ui.sync.DriveBackupResolver
import com.kevin.legion.ui.sync.GoogleGrantResolver
import com.kevin.legion.ui.sync.DriveConnectionRow
import com.kevin.legion.ui.sync.LocalRecoveryRow
import com.kevin.legion.ui.sync.RestoreConfirmDialog
import com.kevin.legion.ui.sync.SyncNowRow
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.shortDate
import kotlinx.coroutines.launch

/**
 * `settings/drive-sync` - the "Connect Google Drive" screen. Before this
 * screen existed, sync was structurally unreachable (traced 2026-08-03):
 * [MainActivity.onResume] already called [SyncEngine.maybeAutoSync] on every
 * foreground resume, but [SyncCapability.syncAvailable] gates that on
 * [CompanionProfile.isSyncEnabled], and nothing ever called
 * [CompanionProfile.setSyncEnabled] - so the flag could never become true,
 * and even if it had been flipped by hand, nothing launched the consent
 * [android.app.PendingIntent] [DriveAuth.authorize] returns via
 * [DriveAuth.Outcome.NeedsConsent]. This screen is that missing entry point,
 * not a rework of [SyncEngine]'s merge/registry logic.
 *
 * Same shape as [KeyScreen] (the BYO Gemini key screen this is modelled on):
 * a single setup task, a validation/consent round-trip, a persisted result,
 * clear success/failure states. The one real difference is the consent step
 * itself, which - unlike the key screen's plain suspend call - can require
 * launching an interactive system Activity via
 * [androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult]
 * and feeding its result back through [DriveAuth.tokenFromConsent].
 *
 * **[CompanionProfile.setSyncEnabled] is only ever called with `true` after a
 * token is actually in hand** ([connect]'s `Authorized` branch, or the
 * launcher callback's non-null-token branch) - never optimistically before
 * the round trip resolves. A flag set ahead of a real grant would make
 * [SyncEngine.maybeAutoSync] fire on the very next resume and fail silently
 * against a Drive session that was never actually authorized.
 *
 * **Failures are diagnosed, not collapsed (fixed 2026-08-03).** [DriveAuth.authorize]'s
 * [DriveAuth.Outcome.Failed] and [DriveAuth.tokenFromConsent]'s
 * [DriveAuth.ConsentResult.Failed] both carry a real [Throwable]; this screen runs it
 * through [GoogleGrantResolver.diagnose] rather than showing a single generic
 * "wasn't connected" for every cause. In particular a Play Services `DEVELOPER_ERROR`
 * (this build's package name and signing certificate not registered for Drive access -
 * see [DriveAuth]'s own doc comment on the tracked clone-and-run blocker) now says so
 * in plain language instead of reading identically to the user tapping cancel. Every
 * diagnosed failure is also logged through [MidnightEvents.recordError] so it is
 * visible in logcat even though there is no crash reporter wired up (CLAUDE.md 3).
 */
@Composable
fun DriveSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var playServicesAvailable by remember { mutableStateOf(SyncCapability.playServicesAvailable(context)) }
    var syncEnabled by remember { mutableStateOf(CompanionProfile.isSyncEnabled(context)) }
    var working by remember { mutableStateOf(false) }
    var connectStatus by remember { mutableStateOf<String?>(null) }
    // Set alongside connectStatus so the message can be drawn in the quarantine
    // color for a real failure, vs. the ordinary/faint color for success or a
    // plain cancel - same distinction SyncNowRow already draws on `ok == false`.
    var connectStatusIsError by remember { mutableStateOf(false) }
    var syncOk by remember { mutableStateOf<Boolean?>(null) }
    var syncMessage by remember { mutableStateOf<String?>(null) }

    // Whole-database backup/restore state (Phase 0, DatabaseSnapshot) - deliberately
    // separate from the row-level syncOk/syncMessage pair above, same split as
    // DatabaseSnapshot vs SyncEngine themselves (see DatabaseSnapshot's class doc comment).
    var backupWorking by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupMessageIsError by remember { mutableStateOf(false) }
    // Non-null ONLY the moment a routine backup has just been refused - drives whether the
    // separate, deliberate "BACK UP ANYWAY" action even appears (Ravi's review, ALSO-FIX 5).
    // Cleared on every other outcome so the override never lingers as an always-available button.
    var backupRefusalReason by remember { mutableStateOf<String?>(null) }
    // Non-null shows the override confirm dialog; the override itself only fires from there,
    // never directly from the row's button.
    var overrideConfirmOpen by remember { mutableStateOf(false) }
    var generations by remember { mutableStateOf<List<DatabaseSnapshot.Generation>>(emptyList()) }
    // The generation awaiting the deliberate confirm dialog (task brief: "requires a
    // deliberate confirm"). Non-null shows RestoreConfirmDialog; null shows nothing.
    var restoreTarget by remember { mutableStateOf<DatabaseSnapshot.Generation?>(null) }
    var restoreWorking by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }
    // True once DatabaseSnapshot.restore actually replaced the live db file - from this
    // point the app MUST restart before anything touches Room again (see
    // DatabaseSnapshot.restore's own doc comment, step 6), so the rest of the screen is
    // replaced by a blocking restart prompt rather than staying interactive.
    var restoreCompleted by remember { mutableStateOf(false) }

    // Local (non-Drive) recovery points - pre-restore safety copies, plus an interrupted
    // restore's original if one is stranded (Ravi's review, ALSO-FIX 7). Loaded independently
    // of Drive connection state: these never left the device, so they're worth showing even
    // if syncEnabled is currently false.
    var localRecoveries by remember { mutableStateOf<List<DatabaseSnapshot.LocalRecovery>>(emptyList()) }
    var localRecoveryTarget by remember { mutableStateOf<DatabaseSnapshot.LocalRecovery?>(null) }
    var localRecoveryWorking by remember { mutableStateOf(false) }
    var localRecoveryMessage by remember { mutableStateOf<String?>(null) }

    // Scheduled (automatic) backup status - a SEPARATE fact from the "generations available"
    // list above. That list is whatever is on Drive right now, backed up by a manual tap OR
    // ScheduledBackup; these two fields are specifically "did the automatic daily check last
    // actually succeed, and if not why" - ticket point 3's whole reason for existing: a
    // background job that fails silently reports safety it is not providing, so this must be
    // able to say "no backup yet" or name a real failure, never render absence as freshness.
    var scheduledLastSuccessAt by remember { mutableStateOf<Long?>(null) }
    var scheduledLastFailureReason by remember { mutableStateOf<String?>(null) }

    fun refreshLocalRecoveries() {
        scope.launch { localRecoveries = DatabaseSnapshot.listLocalRecoveries(context) }
    }

    // Both flags are re-read on every resume, not just once at composition -
    // the same "cheap, not a poll" shape LedgerScreen/SettingsScreen use for
    // their own on-resume refreshes. Play Services can change (an update
    // finishing in the background) and the sync flag can change from a
    // Disconnect tap made on this same screen or a profile switch elsewhere.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        playServicesAvailable = SyncCapability.playServicesAvailable(context)
        syncEnabled = CompanionProfile.isSyncEnabled(context)
        if (syncEnabled) scope.launch { generations = DatabaseSnapshot.listGenerations(context) }
        refreshLocalRecoveries()
        scheduledLastSuccessAt = com.kevin.legion.sync.ScheduledBackup.lastSuccessAt(context)
        scheduledLastFailureReason = com.kevin.legion.sync.ScheduledBackup.lastFailureReason(context)
    }

    fun formatBackupTime(epochMs: Long): String = "${shortDate(epochMs)} ${clockTime(epochMs)}"

    fun backupNow(overrideGuard: Boolean = false) {
        backupWorking = true
        backupMessage = null
        backupRefusalReason = null
        scope.launch {
            when (val result = DatabaseSnapshot.backupNow(context, overrideGuard = overrideGuard)) {
                is DatabaseSnapshot.BackupResult.Ok -> {
                    backupMessage = "Backed up - ${result.generation.rowCount} rows."
                    backupMessageIsError = false
                    generations = DatabaseSnapshot.listGenerations(context)
                }
                is DatabaseSnapshot.BackupResult.Refused -> {
                    backupMessage = result.reason
                    backupMessageIsError = true
                    backupRefusalReason = result.reason
                }
                is DatabaseSnapshot.BackupResult.Failed -> {
                    backupMessage = result.reason
                    backupMessageIsError = true
                }
            }
            backupWorking = false
        }
    }

    fun performRestore(generation: DatabaseSnapshot.Generation) {
        restoreWorking = true
        restoreMessage = null
        scope.launch {
            when (val result = DatabaseSnapshot.restore(context, generation)) {
                DatabaseSnapshot.RestoreResult.Ok -> restoreCompleted = true
                is DatabaseSnapshot.RestoreResult.Refused -> restoreMessage = result.reason
                is DatabaseSnapshot.RestoreResult.Failed -> {
                    restoreMessage = result.reason
                    refreshLocalRecoveries() // a failed install may have left a recoverable artifact
                }
            }
            restoreWorking = false
            restoreTarget = null
        }
    }

    fun performLocalRecoveryRestore(recovery: DatabaseSnapshot.LocalRecovery) {
        localRecoveryWorking = true
        localRecoveryMessage = null
        scope.launch {
            when (val result = DatabaseSnapshot.restoreFromLocal(context, recovery)) {
                DatabaseSnapshot.RestoreResult.Ok -> restoreCompleted = true
                is DatabaseSnapshot.RestoreResult.Refused -> localRecoveryMessage = result.reason
                is DatabaseSnapshot.RestoreResult.Failed -> localRecoveryMessage = result.reason
            }
            localRecoveryWorking = false
            localRecoveryTarget = null
            refreshLocalRecoveries()
        }
    }

    // Step 2 of the consent round trip: the system Activity DriveAuth.Outcome.NeedsConsent's
    // PendingIntent launches has returned. DriveAuth.tokenFromConsent now distinguishes a
    // real token, a plain cancel, and a genuine failure carrying a Throwable - see its own
    // doc comment for why that used to collapse into a single nullable String.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        when (val outcome = DriveAuth.tokenFromConsent(context, result.data)) {
            is DriveAuth.ConsentResult.Token -> {
                CompanionProfile.setSyncEnabled(context, true)
                syncEnabled = true
                connectStatus = GoogleGrantResolver.CONNECTED_MESSAGE
                connectStatusIsError = false
            }
            is DriveAuth.ConsentResult.Cancelled -> {
                connectStatus = GoogleGrantResolver.CANCELLED_MESSAGE
                connectStatusIsError = false
            }
            is DriveAuth.ConsentResult.Failed -> {
                val failure = GoogleGrantResolver.diagnose(
                    grant = GoogleGrantResolver.Grant.DRIVE,
                    statusCode = DriveAuth.statusCodeOf(outcome.error),
                    isNetworkException = DriveAuth.looksLikeNetworkFailure(outcome.error),
                    fallbackMessage = outcome.error.message,
                )
                MidnightEvents.recordError("drive_connect_consent", outcome.error)
                connectStatus = failure.message
                connectStatusIsError = true
            }
        }
        working = false
    }

    fun connect() {
        connectStatus = null
        working = true
        // rememberCoroutineScope, not a scope built inline in this function -
        // see SyncEngine.engineScope's doc comment for the exact anti-pattern
        // (a fresh CoroutineScope(...) per call, no owner, no cancellation)
        // this repo is actively removing. A UI-triggered click uses the
        // Compose-aware scope instead, same as KeyScreen.verify.
        scope.launch {
            when (val outcome = DriveAuth.authorize(context)) {
                is DriveAuth.Outcome.Authorized -> {
                    CompanionProfile.setSyncEnabled(context, true)
                    syncEnabled = true
                    connectStatus = GoogleGrantResolver.CONNECTED_MESSAGE
                    connectStatusIsError = false
                    working = false
                }
                is DriveAuth.Outcome.NeedsConsent -> {
                    // working stays true across the launch - the consentLauncher
                    // callback above is what clears it, once the round trip
                    // actually resolves one way or the other.
                    consentLauncher.launch(
                        IntentSenderRequest.Builder(outcome.pendingIntent.intentSender).build(),
                    )
                }
                is DriveAuth.Outcome.Failed -> {
                    val failure = GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.DRIVE,
                        statusCode = DriveAuth.statusCodeOf(outcome.error),
                        isNetworkException = DriveAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    )
                    MidnightEvents.recordError("drive_connect", outcome.error)
                    connectStatus = failure.message
                    connectStatusIsError = true
                    working = false
                }
            }
        }
    }

    fun disconnect() {
        CompanionProfile.setSyncEnabled(context, false)
        syncEnabled = false
        connectStatus = "Disconnected. Nothing further syncs until you connect again."
        connectStatusIsError = false
        syncOk = null
        syncMessage = null
        // The backup/restore panel is CONNECTED-gated the same way sync-now is - clear its
        // state too rather than leaving a stale generations list visible under a
        // now-disabled BACK UP NOW button.
        generations = emptyList()
        backupMessage = null
    }

    fun syncNow() {
        working = true
        syncMessage = null
        scope.launch {
            val result = SyncEngine.syncNow(context)
            syncOk = result.ok
            syncMessage = result.message
            working = false
        }
    }

    val availability = GoogleGrantResolver.availability(playServicesAvailable, syncEnabled)
    val sem = LocalLegionSemantics.current

    // Step 6 of DatabaseSnapshot.restore's sequence: once the live db file has actually
    // been replaced, the rest of this screen (and the rest of the app) must not be touched
    // again until a full process restart - see that function's class doc comment for why
    // an in-place "keep going" is unsafe (every OTHER in-memory cache/controller in the
    // process still holds state read from the database that no longer exists). This
    // replaces the whole screen with a blocking prompt rather than letting the driver
    // navigate away from it.
    if (restoreCompleted) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp)) {
                Text("Restore complete", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Your database was replaced with the restored backup. Legion must restart to " +
                        "finish - nothing else in the app can be trusted to reflect it until then.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = sem.faint,
                )
                Spacer(Modifier.height(20.dp))
                Button(onClick = { restartApp(context) }) {
                    Text("RESTART NOW")
                }
            }
        }
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Drive sync", onBack = onBack)

            Column(
                Modifier
                    .padding(horizontal = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(8.dp))

                Text(
                    "Ledger and pantry data can sync across your own devices through your own " +
                        "Google Drive. Nothing goes through a server I run - this is your Drive, " +
                        "not mine.",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )

                Spacer(Modifier.height(8.dp))
                DriveConnectionRow(
                    availability = availability,
                    working = working,
                    onConnect = ::connect,
                    onDisconnect = ::disconnect,
                )

                connectStatus?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        // ADVISORY (ticket 13 re-home): a connect failure, not a failed gate.
                        color = if (connectStatusIsError) sem.estimated else sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                SyncNowRow(
                    canSync = availability == GoogleGrantResolver.Availability.CONNECTED,
                    working = working,
                    ok = syncOk,
                    message = syncMessage,
                    onSyncNow = ::syncNow,
                )

                if (syncEnabled) {
                    Spacer(Modifier.height(16.dp))
                    DatabaseBackupRow(
                        canBackUp = availability == GoogleGrantResolver.Availability.CONNECTED,
                        working = backupWorking,
                        summary = DriveBackupResolver.lastBackupSummary(generations, ::formatBackupTime),
                        resultMessage = backupMessage,
                        resultIsError = backupMessageIsError,
                        onBackUpNow = { backupNow() },
                        onBackUpAnywayRequested = backupRefusalReason?.let { { overrideConfirmOpen = true } },
                    )

                    // The automatic daily check's own status - honest about absence, per
                    // ticket point 3: a background job that fails silently reports safety it
                    // is not providing, so this is worded plainly rather than left implicit
                    // from the "generations available" list above (which a manual tap can
                    // populate even if the scheduled check has never once succeeded).
                    Text(
                        // The "only while the app is open" caveat belongs on BOTH branches, not
                        // just the empty one. A bare date on the succeeded branch lets a five-day-
                        // old backup read as a daily one, which is the same overstatement this
                        // block exists to prevent - MIN_INTERVAL_MS is a floor on attempts, never
                        // a promise of freshness (see ScheduledBackup's own doc comment).
                        text = if (scheduledLastSuccessAt != null) {
                            "Automatic backup: last succeeded ${formatBackupTime(scheduledLastSuccessAt!!)}. " +
                                "Legion checks once a day, only while the app is open."
                        } else {
                            "Automatic backup: none yet. Legion checks once a day, only while the app is open."
                        },
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    scheduledLastFailureReason?.let {
                        Text(
                            text = "Last automatic attempt failed: $it",
                            style = LegionType.stamp,
                            // ADVISORY (ticket 13 re-home): a background attempt's own result,
                            // same colour rule the manual-backup/restore messages above use.
                            color = sem.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }

                    if (generations.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Backups on Drive (newest 3 kept)",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        val rows = DriveBackupResolver.generationRows(
                            generations = generations,
                            runningSchemaVersion = CarDatabase.SCHEMA_VERSION,
                            formatTime = ::formatBackupTime,
                        )
                        for (row in rows) {
                            BackupGenerationRow(row = row, onRestore = { restoreTarget = row.generation })
                        }
                    }

                    restoreMessage?.let {
                        // ADVISORY (ticket 13 re-home): a restore result, not a failed gate.
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }

                // Recover-locally panel - independent of syncEnabled/Drive connection state,
                // since these files never left the device (Ravi's review, ALSO-FIX 7).
                if (localRecoveries.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Recover locally (no Drive needed)",
                        style = LegionType.stamp,
                        color = sem.faint,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    val localRows = DriveBackupResolver.localRecoveryRows(localRecoveries, ::formatBackupTime)
                    for (row in localRows) {
                        LocalRecoveryRow(row = row, onRestore = { localRecoveryTarget = row.recovery })
                    }
                    localRecoveryMessage?.let {
                        // ADVISORY (ticket 13 re-home): a recovery result, not a failed gate.
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = sem.estimated,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    restoreTarget?.let { target ->
        RestoreConfirmDialog(
            message = DriveBackupResolver.confirmRestoreMessage(target, ::formatBackupTime),
            onConfirm = { performRestore(target) },
            onDismiss = { restoreTarget = null },
        )
    }

    localRecoveryTarget?.let { target ->
        val row = DriveBackupResolver.localRecoveryRows(listOf(target), ::formatBackupTime).single()
        RestoreConfirmDialog(
            message = DriveBackupResolver.confirmLocalRecoveryMessage(row, ::formatBackupTime),
            onConfirm = { performLocalRecoveryRestore(target) },
            onDismiss = { localRecoveryTarget = null },
            title = "Recover this locally?",
            confirmLabel = "Recover",
        )
    }

    if (overrideConfirmOpen && backupRefusalReason != null) {
        RestoreConfirmDialog(
            message = DriveBackupResolver.confirmOverrideGuardMessage(backupRefusalReason!!),
            onConfirm = {
                overrideConfirmOpen = false
                backupNow(overrideGuard = true)
            },
            onDismiss = { overrideConfirmOpen = false },
            title = "Back up anyway?",
            confirmLabel = "Back up anyway",
        )
    }
}

/**
 * Fully restarts the app process - relaunches the launcher Activity fresh, then kills this
 * process. This is the ONLY way to guarantee every in-memory cache/controller in the
 * process re-reads from the just-restored database rather than serving stale state read
 * before [DatabaseSnapshot.restore] ran - see that function's doc comment, step 6.
 * [Runtime.exit] rather than [System.exit] for the same reason the rest of this codebase
 * uses `Runtime.getRuntime()` call sites elsewhere: it is the one guaranteed to actually
 * terminate the JVM without running finalizers that could touch the now-replaced database.
 */
private fun restartApp(context: Context) {
    context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    Runtime.getRuntime().exit(0)
}
