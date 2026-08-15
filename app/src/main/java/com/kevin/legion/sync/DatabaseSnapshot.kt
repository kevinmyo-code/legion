package com.kevin.legion.sync

import android.content.Context
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.ui.sync.GoogleGrantResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Whole-database backup/restore to the driver's own Google Drive `appDataFolder` - Phase 0
 * of the sync overhaul (Kevin, 2026-08-12), and deliberately INDEPENDENT of [SyncEngine]'s
 * row-level merge/[SyncMerge]/`REGISTRY`. Do not fold this into that machinery; see below
 * for why they solve different problems now.
 *
 * **The incident this exists for.** 2026-08-12: an older APK was installed over schema v15.
 * Room's `fallbackToDestructiveMigrationOnDowngrade` fallback saw a downgrade and dropped
 * all 42 tables with no prompt, no log line, no crash (that fallback is already removed,
 * commit `deeddd9`, [CarDatabase]'s own doc comment has the full story). Only the 19 tables
 * [SyncEngine.REGISTRY] covers were recoverable from Drive; the other 23 - lists, ledger,
 * pantry, workouts, meals, sleep, budgets - were gone. [SyncEngine] syncs individual TABLES
 * an app version knows how to merge; it structurally cannot protect a table it doesn't know
 * about yet, or protect against the schema itself being wrong. A whole-file snapshot does
 * not have that blind spot - it backs up bytes, not a registry of known shapes.
 *
 * **"One user, one Drive" (Kevin, stated directly to the orchestrator 2026-08-12) supersedes
 * CLAUDE.md §2's "one shared Google account, two phones" for this feature.** This is BACKUP
 * AND RESTORE for one person's one device, not cross-device convergence - which is exactly
 * why a whole-DB snapshot is the right shape here and row-level merge (which exists to
 * reconcile two devices editing concurrently) is not. [SyncEngine] is unaffected and
 * untouched by this file. (Noted per review: CLAUDE.md §2 itself has not been rewritten to
 * say this yet - that edit is Kevin's to make, not implied by this doc comment.)
 *
 * ## Export mechanism - VACUUM INTO, tested not to be universally available
 *
 * `VACUUM INTO` (SQLite 3.27+) produces a consistent copy of a live database without
 * halting writes - the ideal primitive here, since Room may be mid-write on a background
 * thread while a backup runs. **This was verified, not assumed**: [DatabaseSnapshotExportTest]
 * ran it against a real Room-managed WAL database under Robolectric's bundled SQLite
 * (`sqlite4java`, a statically-linked build old enough to predate 3.27) and it threw
 * `near "INTO": syntax error` - a real, reproduced failure, not a documentation-only
 * concern. That failure is exactly the shape minSdk 24 (Android 7) risks on a real
 * device too: Android 7's platform `libsqlite3` predates 3.27 as well, and this app has
 * no bundled/newer SQLite dependency (no `requery:sqlite-android`, no androidx bundled
 * driver) - it uses whatever `libsqlite3.so` the OS ships.
 *
 * So [exportLocalCopy] does not depend on `VACUUM INTO` unconditionally: it attempts it
 * first, and on ANY failure (old SQLite, disk pressure, anything) falls back to a
 * `PRAGMA wal_checkpoint(TRUNCATE)` + plain file copy of the live `.db` file. The
 * checkpoint merges the WAL back into the main file and truncates it, so the copy that
 * follows is of a single self-contained file - not as strong a consistency guarantee as
 * `VACUUM INTO` (a write landing between the checkpoint and the copy could still be
 * missed, since nothing blocks writers during a plain file copy the way `VACUUM INTO`'s
 * internal snapshot does), but it is the best available fallback on a platform SQLite too
 * old for the ideal path. Kevin's own test device (Oppo A17K, Android 12/13) almost
 * certainly has a modern-enough SQLite for `VACUUM INTO` to succeed there; the fallback
 * exists for the minSdk 24 floor this app declares, which that one device cannot speak for.
 *
 * ## Generations
 *
 * Uploads are named `legion_backup_<epochMillis>.db.gz` + a companion
 * `legion_backup_<epochMillis>.meta.json` (schema `user_version` + total row count) - a
 * separate small JSON file rather than embedding metadata in the `.db.gz` itself, so
 * [listGenerations] (driving the settings UI's "generations available" list) can learn a
 * generation's row count and schema version with one small download instead of pulling
 * and gunzipping the whole database just to read two numbers.
 *
 * The last [MAX_GENERATIONS] are kept. **The oldest is deleted only after a new one has
 * uploaded successfully** ([backupNow] calls [pruneOldGenerations] as the very last step,
 * after both the `.db.gz` and `.meta.json` for the NEW generation are confirmed on Drive) -
 * never overwriting the single most recent good copy in place, and never deleting before
 * the replacement is confirmed present. Ravi's review confirmed this specific property
 * (pruning can never leave zero backups on Drive) as correct and traced.
 *
 * **The refusal gate ([DatabaseSnapshotGuard]) catches the wipe case even earlier**, before
 * any upload is attempted at all - but a refusal alone would permanently lock out a driver
 * who legitimately deletes a lot of data on purpose (a real bulk telemetry purge, say), so
 * [backupNow]'s `overrideGuard` parameter exists as a distinct, deliberate escape hatch -
 * see its own doc comment. It is never the default and the UI never offers it as the first
 * action; it only appears after a routine backup has already been refused once.
 *
 * ## Restore - manual only, never automatic
 *
 * [restore] is never called from a sync pass, a merge, or first launch - the UI (settings
 * `drive-sync` screen) is the only caller, gated behind an explicit, worded confirmation
 * naming what will be overwritten (CLAUDE.md §7: worded, never colour-only). The shared
 * install mechanics live in [installDatabaseFile] (used by both [restore] and
 * [restoreFromLocal]):
 *  1. [restore] refuses outright if the chosen generation's `schemaVersion` is NEWER than
 *     the running app's ([CarDatabase]'s live `PRAGMA user_version`) - restoring a newer
 *     schema into an older build is the exact shape of how the 2026-08-12 incident
 *     happened, just with the direction reversed.
 *  2. Downloads and gunzips the backup, sniffs the SQLite file-header magic bytes before
 *     trusting it as a database at all.
 *  3. [installDatabaseFile] refuses if a PRIOR restore attempt left an unresolved
 *     `.replaced-by-restore` artifact on disk, rather than silently deleting it - see
 *     [listLocalRecoveries]/[restoreFromLocal] for how the driver recovers it by tapping
 *     instead of by adb (Ravi's review, BLOCKING finding 6 / ALSO-FIX 7).
 *  4. Explicitly checkpoints the live WAL (`PRAGMA wal_checkpoint(TRUNCATE)`) on the STILL
 *     OPEN connection, then takes a LOCAL (not uploaded) safety snapshot of the current
 *     database via [exportLocalCopy] - belt and braces (Ravi's review, ALSO-FIX 4): the
 *     explicit checkpoint plus [exportLocalCopy]'s own `VACUUM INTO`/checkpoint-copy makes
 *     this snapshot the trusted, verified-consistent rollback source, not an assumption
 *     about what `close()` leaves behind.
 *  5. Holds [CarDatabase.withDatabaseLock] across the ENTIRE close-then-replace-file
 *     window (Ravi's review, BLOCKING finding 2) - see that function's doc comment for the
 *     concurrent-`getDatabase()` race this closes (`TelemetryRecorder`'s 30-second OBD
 *     write loop, most concretely). Inside the lock: [CarDatabase.closeAndClear], move the
 *     live `.db` aside (not deleted), install the restored file, and on EITHER a thrown
 *     exception OR a plain `false` return from the install rename (Ravi's review, BLOCKING
 *     finding 1 - `File.renameTo` is documented to throw `SecurityException`), roll back
 *     from the STEP-4 safety snapshot, never from the moved-aside raw file (ALSO-FIX 4).
 *  6. Returns [RestoreResult.Ok], which the UI must treat as "the app must fully restart
 *     before anything touches Room again" - [CarDatabase.getDatabase] would otherwise happily
 *     open a brand new connection pool against the restored file mid-session, which is
 *     fine in isolation, but every OTHER in-memory cache/controller in the process (much of
 *     `ai/`, `ledger/`, `pantry/`, `vehicle/`) still holds state read from the database that
 *     no longer exists. A full process relaunch is the only way to guarantee nothing reads
 *     stale in-memory state after a restore; this file does not attempt to restart the
 *     process itself (that's an Activity-level concern - see `ui/DriveSyncScreen.kt`).
 */
object DatabaseSnapshot {
    private const val TAG = "DatabaseSnapshot"
    private const val NAME_PREFIX = "legion_backup_"
    private const val DB_SUFFIX = ".db.gz"
    private const val META_SUFFIX = ".meta.json"

    /** How many generations are kept on Drive (and, separately, how many local
     * pre-restore safety copies are kept - see [prunePreRestoreBackups]). */
    const val MAX_GENERATIONS = 3

    /** Which path [exportLocalCopy] actually took - exposed so tests can pin BOTH branches,
     * not just whichever one the test environment happens to support. */
    enum class ExportMethod { VACUUM_INTO, CHECKPOINT_COPY }

    /** Schema `user_version` + total user-row count recorded alongside a generation. */
    data class Metadata(val timestampMs: Long, val schemaVersion: Int, val rowCount: Long) {
        fun toJsonBytes(): ByteArray =
            JSONObject()
                .put("timestampMs", timestampMs)
                .put("schemaVersion", schemaVersion)
                .put("rowCount", rowCount)
                .toString()
                .toByteArray(Charsets.UTF_8)

        companion object {
            /** Null on any parse failure - a corrupt/foreign `.meta.json` should drop that
             * generation from the list rather than crash the settings screen. */
            fun fromJsonBytes(bytes: ByteArray): Metadata? = runCatching {
                val o = JSONObject(String(bytes, Charsets.UTF_8))
                Metadata(o.getLong("timestampMs"), o.getInt("schemaVersion"), o.getLong("rowCount"))
            }.getOrNull()
        }
    }

    /** One backup generation as visible from Drive - what the settings screen lists. */
    data class Generation(
        val timestampMs: Long,
        val schemaVersion: Int,
        val rowCount: Long,
        val dbFileId: String,
        val metaFileId: String,
    )

    /** What kind of on-device (not Drive) recovery point a [LocalRecovery] entry is. */
    enum class LocalRecoveryKind {
        /** A verified-consistent snapshot [installDatabaseFile] took of the CURRENT database
         * immediately before a restore ran - the normal undo path. */
        PRE_RESTORE_COPY,

        /** The raw original database, moved aside during a restore attempt that did not
         * finish cleanly. Recovering this is a last resort - see [restoreFromLocal]. */
        INTERRUPTED_ORIGINAL,
    }

    /** One locally-recoverable database file - never requires Drive or adb to use. Surfaced
     * by the settings screen so a stranded pre-restore copy or an interrupted restore's
     * original is something the driver can tap to restore, not a raw file path in an error
     * string (Ravi's review, ALSO-FIX 7). */
    data class LocalRecovery(val file: File, val timestampMs: Long, val kind: LocalRecoveryKind, val label: String)

    sealed interface BackupResult {
        data class Ok(val generation: Generation) : BackupResult
        /** [DatabaseSnapshotGuard] refused the upload - the previous good generation is untouched. */
        data class Refused(val reason: String) : BackupResult
        data class Failed(val reason: String) : BackupResult
    }

    sealed interface RestoreResult {
        /** The live database file was replaced. The caller MUST restart the app before any
         * further Room access - see this object's class doc comment, step 6. */
        data object Ok : RestoreResult
        data class Refused(val reason: String) : RestoreResult
        data class Failed(val reason: String) : RestoreResult
    }

    // --------------------------------------------------------------------- listing

    /** Every backup generation on Drive, newest first. Empty (never throws) if Drive isn't
     * connected or reachable - same fail-soft shape as [SyncEngine]. */
    suspend fun listGenerations(context: Context): List<Generation> = withContext(Dispatchers.IO) {
        if (!SyncCapability.syncAvailable(context)) return@withContext emptyList()
        // tokenOrReason, not accessTokenOrNull: distinguishes a lapsed/revoked grant
        // (logged plainly as needing re-authorising) from a genuine failure, even though
        // this function's own return shape stays fail-soft-empty either way - see
        // SyncEngine.syncNow's doc comment on the same tokenOrReason swap for why the
        // collapse this replaces was a live defect, not just an API nicety.
        val token = try {
            when (val outcome = DriveAuth.tokenOrReason(context)) {
                is DriveAuth.TokenResult.Token -> outcome.accessToken
                DriveAuth.TokenResult.NeedsConsent -> {
                    Log.w(TAG, "listGenerations: Drive needs re-authorising")
                    null
                }
                is DriveAuth.TokenResult.Failed -> {
                    Log.w(TAG, "listGenerations: couldn't get a Drive token", outcome.error)
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listGenerations: couldn't get a Drive token", e)
            null
        } ?: return@withContext emptyList()
        val drive = DriveClient(token)
        generationsFrom(drive, drive.listAppData()).sortedByDescending { it.timestampMs }
    }

    /** Every locally-recoverable database file (pre-restore safety copies, plus a leftover
     * interrupted-restore original if one exists), newest first. Needs no network - these
     * never left the device. */
    suspend fun listLocalRecoveries(context: Context): List<LocalRecovery> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LocalRecovery>()
        val preRestoreDir = File(context.filesDir, "pre_restore_backups")
        preRestoreDir.listFiles()?.filter { it.isFile }?.forEach { f ->
            val ts = f.name.removePrefix("pre_restore_").removeSuffix(".db").toLongOrNull() ?: f.lastModified()
            out.add(LocalRecovery(f, ts, LocalRecoveryKind.PRE_RESTORE_COPY, "Safety copy from before a restore"))
        }
        val movedAside = movedAsideFile(context)
        if (movedAside.exists()) {
            out.add(
                LocalRecovery(
                    movedAside, movedAside.lastModified(), LocalRecoveryKind.INTERRUPTED_ORIGINAL,
                    "Your database from an interrupted restore",
                ),
            )
        }
        out.sortedByDescending { it.timestampMs }
    }

    /** Reads every `legion_backup_*` pair present in [existing], downloading each small
     * `.meta.json` (never the `.db.gz`) to learn its row count/schema version. */
    private fun generationsFrom(drive: DriveClient, existing: Map<String, DriveClient.DriveFile>): List<Generation> {
        val out = mutableListOf<Generation>()
        for ((name, file) in existing) {
            if (!name.startsWith(NAME_PREFIX) || !name.endsWith(DB_SUFFIX)) continue
            val ts = name.removePrefix(NAME_PREFIX).removeSuffix(DB_SUFFIX).toLongOrNull() ?: continue
            val metaFile = existing[metaName(ts)] ?: continue
            val metaBytes = drive.download(metaFile.id) ?: continue
            val meta = Metadata.fromJsonBytes(metaBytes) ?: continue
            out.add(Generation(ts, meta.schemaVersion, meta.rowCount, file.id, metaFile.id))
        }
        return out
    }

    private fun dbName(ts: Long) = "$NAME_PREFIX$ts$DB_SUFFIX"
    private fun metaName(ts: Long) = "$NAME_PREFIX$ts$META_SUFFIX"

    // --------------------------------------------------------------------- backup

    /**
     * Runs one backup pass: guard, export, gzip, upload, prune. Never throws.
     *
     * [overrideGuard] bypasses [DatabaseSnapshotGuard.shouldRefuse] - a distinct, deliberate
     * escape hatch (Ravi's review, ALSO-FIX 5) for the driver who legitimately deleted a lot
     * of data on purpose and would otherwise be permanently locked out of backing up (the
     * guard never updates its own baseline, so a real refusal never "expires" on its own).
     * Defaults `false`; the UI only ever passes `true` from a SEPARATE, explicitly-worded
     * confirm action offered AFTER a routine backup has already been refused once - never
     * as part of the normal BACK UP NOW flow. An override is still logged (not silent).
     */
    suspend fun backupNow(context: Context, overrideGuard: Boolean = false): BackupResult = withContext(Dispatchers.IO) {
        if (!SyncCapability.syncAvailable(context)) {
            return@withContext BackupResult.Failed("Google Drive isn't connected.")
        }
        val token = try {
            when (val outcome = DriveAuth.tokenOrReason(context)) {
                is DriveAuth.TokenResult.Token -> outcome.accessToken
                DriveAuth.TokenResult.NeedsConsent ->
                    return@withContext BackupResult.Failed(
                        GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.DRIVE),
                    )
                is DriveAuth.TokenResult.Failed -> {
                    val failure = GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.DRIVE,
                        statusCode = DriveAuth.statusCodeOf(outcome.error),
                        isNetworkException = DriveAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    )
                    Log.w(TAG, "backupNow: couldn't get a Drive token", outcome.error)
                    MidnightEvents.recordError("db_snapshot_auth", outcome.error)
                    return@withContext BackupResult.Failed(failure.message)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "backupNow: couldn't get a Drive token", e)
            MidnightEvents.recordError("db_snapshot_auth", e)
            return@withContext BackupResult.Failed("Couldn't reach your Google Drive - try again.")
        }

        try {
            val drive = DriveClient(token)
            val prior = generationsFrom(drive, drive.listAppData()).maxByOrNull { it.timestampMs }

            val rowCount = countUserRows(context)
            val refuse = !overrideGuard && DatabaseSnapshotGuard.shouldRefuse(rowCount, prior?.rowCount)
            if (refuse) {
                val reason = DatabaseSnapshotGuard.refusalReason(rowCount, prior!!.rowCount)
                Log.w(TAG, "backup refused: $reason")
                MidnightEvents.recordError("db_snapshot_refused", IllegalStateException(reason))
                return@withContext BackupResult.Refused(reason)
            }
            if (overrideGuard && prior != null && DatabaseSnapshotGuard.shouldRefuse(rowCount, prior.rowCount)) {
                // The guard WOULD have refused - proceeding only because the driver explicitly
                // chose to. Logged as an audit trail, never silent.
                Log.w(TAG, "backup guard OVERRIDDEN by explicit action: $rowCount rows vs prior ${prior.rowCount}")
                MidnightEvents.recordError(
                    "db_snapshot_guard_overridden",
                    IllegalStateException("rowCount=$rowCount prior=${prior.rowCount}"),
                )
            }

            val ts = System.currentTimeMillis()
            val tmp = File(context.cacheDir, "db_snapshot_$ts.db")
            try {
                exportLocalCopy(context, tmp)
                val gz = gzipFile(tmp)
                val schemaVersion = currentSchemaVersion(context)
                val meta = Metadata(ts, schemaVersion, rowCount)

                val dbId = drive.create(dbName(ts), gz)
                    ?: return@withContext BackupResult.Failed("Upload failed.")
                val metaId = drive.create(metaName(ts), meta.toJsonBytes())
                if (metaId == null) {
                    // The .db.gz landed but its metadata didn't - don't leave an orphan
                    // listGenerations() can never surface (it requires the meta pair to
                    // exist). Best-effort cleanup; if THIS also fails, the orphan is
                    // harmless (just invisible), never destructive.
                    drive.delete(dbId)
                    return@withContext BackupResult.Failed("Upload failed (metadata).")
                }

                pruneOldGenerations(drive)
                BackupResult.Ok(Generation(ts, schemaVersion, rowCount, dbId, metaId))
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "backup failed", e)
            MidnightEvents.recordError("db_snapshot_backup", e)
            BackupResult.Failed("Backup couldn't finish - I'll try again next time.")
        }
    }

    /** Deletes every generation past [MAX_GENERATIONS], oldest first. Called ONLY after a
     * new generation's `.db.gz` AND `.meta.json` are both confirmed uploaded (see
     * [backupNow]) - re-lists fresh from Drive rather than trusting the caller's
     * pre-upload snapshot, so pruning is always decided from what is actually there. */
    private fun pruneOldGenerations(drive: DriveClient) {
        val gens = generationsFrom(drive, drive.listAppData()).sortedByDescending { it.timestampMs }
        if (gens.size <= MAX_GENERATIONS) return
        for (old in gens.drop(MAX_GENERATIONS)) {
            drive.delete(old.dbFileId)
            drive.delete(old.metaFileId)
        }
    }

    // --------------------------------------------------------------------- restore (from Drive)

    /** Restores [generation] over the live database. See this object's class doc comment
     * for the full sequence and why each step is ordered the way it is. Manual only - the
     * caller (the settings screen) is responsible for requiring an explicit, worded
     * confirmation before calling this, and for restarting the app afterward. */
    suspend fun restore(context: Context, generation: Generation): RestoreResult = withContext(Dispatchers.IO) {
        val runningSchemaVersion = currentSchemaVersion(context)
        if (generation.schemaVersion > runningSchemaVersion) {
            return@withContext RestoreResult.Refused(
                "This backup is from a newer app version (schema v${generation.schemaVersion}) than the " +
                    "one running now (v$runningSchemaVersion). Update the app before restoring it - " +
                    "restoring a newer schema into an older build is how the 2026-08-12 wipe happened.",
            )
        }

        if (!SyncCapability.syncAvailable(context)) {
            return@withContext RestoreResult.Failed("Google Drive isn't connected.")
        }
        val token = try {
            when (val outcome = DriveAuth.tokenOrReason(context)) {
                is DriveAuth.TokenResult.Token -> outcome.accessToken
                DriveAuth.TokenResult.NeedsConsent ->
                    return@withContext RestoreResult.Failed(
                        GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.DRIVE),
                    )
                is DriveAuth.TokenResult.Failed -> {
                    val failure = GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.DRIVE,
                        statusCode = DriveAuth.statusCodeOf(outcome.error),
                        isNetworkException = DriveAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    )
                    Log.w(TAG, "restore: couldn't get a Drive token", outcome.error)
                    MidnightEvents.recordError("db_snapshot_restore_auth", outcome.error)
                    return@withContext RestoreResult.Failed(failure.message)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "restore: couldn't get a Drive token", e)
            MidnightEvents.recordError("db_snapshot_restore_auth", e)
            return@withContext RestoreResult.Failed("Couldn't reach your Google Drive - try again.")
        }

        try {
            val drive = DriveClient(token)
            val bytes = drive.download(generation.dbFileId)
                ?: return@withContext RestoreResult.Failed("Couldn't download that backup.")

            val restoredTmp = File(context.cacheDir, "restore_${generation.timestampMs}.db")
            try {
                gunzipToFile(bytes, restoredTmp)
                if (!looksLikeSqliteFile(restoredTmp)) {
                    return@withContext RestoreResult.Failed(
                        "Downloaded backup doesn't look like a valid database. Nothing was touched.",
                    )
                }
                installDatabaseFile(context, restoredTmp)
            } finally {
                restoredTmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "restore failed", e)
            MidnightEvents.recordError("db_snapshot_restore", e)
            RestoreResult.Failed(
                "Restore couldn't finish. Your original database is safe - check \"Recover locally\" " +
                    "in Drive sync if this keeps happening.",
            )
        }
    }

    // --------------------------------------------------------------------- restore (local recovery)

    /**
     * Restores a [LocalRecovery] entry - no Drive, no network, nothing this device didn't
     * already have on disk. This is the tap-to-recover path Ravi's review asked for
     * (ALSO-FIX 7) instead of the previous behaviour, which left a raw
     * `.replaced-by-restore` file path in an error string with no way to act on it from
     * the UI.
     */
    suspend fun restoreFromLocal(context: Context, recovery: LocalRecovery): RestoreResult = withContext(Dispatchers.IO) {
        try {
            if (!looksLikeSqliteFile(recovery.file)) {
                return@withContext RestoreResult.Failed("That local copy doesn't look like a valid database anymore.")
            }
            when (recovery.kind) {
                // A pre-restore safety copy is a normal, verified-consistent database file -
                // the exact same install mechanics as a Drive generation apply.
                LocalRecoveryKind.PRE_RESTORE_COPY -> installDatabaseFile(context, recovery.file)
                // The interrupted-original case is handled separately: recovery.file IS the
                // `.replaced-by-restore` artifact installDatabaseFile's own re-entry guard
                // refuses to clobber, so installing IT requires bypassing that guard on
                // purpose rather than tripping over it.
                LocalRecoveryKind.INTERRUPTED_ORIGINAL -> installInterruptedOriginal(context, recovery.file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "local recovery failed", e)
            MidnightEvents.recordError("db_snapshot_local_recovery", e)
            RestoreResult.Failed("Local recovery couldn't finish. Nothing extra was deleted.")
        }
    }

    // --------------------------------------------------------------------- shared install mechanics

    private fun movedAsideFile(context: Context): File =
        File(context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME).path + ".replaced-by-restore")

    /**
     * Installs [sourceFile] (an already-validated, on-disk SQLite database) as the live
     * database, replacing whatever is there now. Shared by [restore] (source = a downloaded,
     * gunzipped Drive generation) and [restoreFromLocal]'s `PRE_RESTORE_COPY` branch (source
     * = a local safety copy) - the install mechanics are identical either way; only where
     * [sourceFile] came from differs. See this object's class doc comment for the full
     * numbered sequence this implements.
     */
    private fun installDatabaseFile(context: Context, sourceFile: File): RestoreResult {
        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        val liveWal = File(liveDb.path + "-wal")
        val liveShm = File(liveDb.path + "-shm")
        val liveJournal = File(liveDb.path + "-journal")
        val movedAside = movedAsideFile(context)

        // Ravi's review, BLOCKING finding 6 / ALSO-FIX 7: refuse rather than silently delete
        // a leftover from a previous attempt - it may be the only surviving pre-restore copy.
        // Surfaced to the driver via listLocalRecoveries()/restoreFromLocal(), never by
        // clobbering it here.
        if (movedAside.exists()) {
            return RestoreResult.Failed(
                "A previous restore attempt left an unresolved backup on this device. Resolve it " +
                    "from \"Recover locally\" first - nothing was touched this time.",
            )
        }

        // ALSO-FIX 4 (belt and braces): explicitly checkpoint the live WAL back into the main
        // file on the connection that is about to close, rather than assuming close() does
        // this thoroughly enough on its own.
        val liveHelper = CarDatabase.getDatabase(context).openHelper.writableDatabase
        runCatching { liveHelper.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
            .onFailure { Log.w(TAG, "pre-restore checkpoint failed (non-fatal, exportLocalCopy has its own fallback)", it) }

        // Step 4/verified rollback source: taken while Room is STILL OPEN (exportLocalCopy
        // needs a live connection). ALSO-FIX 4: THIS file, not the raw moved-aside .db below,
        // is what a failed install rolls back from - it is a verified-consistent VACUUM
        // INTO/checkpoint+copy output, not an assumption about what close() leaves behind.
        val preRestoreDir = File(context.filesDir, "pre_restore_backups").apply { mkdirs() }
        val preRestoreFile = File(preRestoreDir, "pre_restore_${System.currentTimeMillis()}.db")
        exportLocalCopy(context, preRestoreFile)
        prunePreRestoreBackups(preRestoreDir)

        // Ravi's review, BLOCKING finding 2: held across the ENTIRE close-then-replace-file
        // window, not just the instant closeAndClear nulls the singleton - see
        // CarDatabase.withDatabaseLock's doc comment for the concurrent-getDatabase() race
        // this closes.
        return CarDatabase.withDatabaseLock {
            CarDatabase.closeAndClear()

            if (liveDb.exists() && !liveDb.renameTo(movedAside)) {
                return@withDatabaseLock RestoreResult.Failed(
                    "Couldn't move the current database aside. Nothing was touched.",
                )
            }
            // Stale WAL/SHM sidecars belong to the OLD generation; leaving them next to the
            // newly-installed file risks Room replaying the wrong write-ahead log against it.
            liveWal.delete()
            liveShm.delete()
            liveJournal.delete()

            // Ravi's review, BLOCKING finding 1: File.renameTo is documented to be able to
            // throw (SecurityException), not just return false - both outcomes must trigger
            // rollback, not just the boolean path the previous version handled.
            val installed = try {
                sourceFile.renameTo(liveDb)
            } catch (t: Throwable) {
                Log.w(TAG, "install rename threw", t)
                false
            }

            if (!installed) {
                return@withDatabaseLock rollbackTo(preRestoreFile, movedAside, liveDb)
            }

            movedAside.delete()
            RestoreResult.Ok
        }
    }

    /**
     * The one place a half-applied restore would be strictly worse than doing nothing.
     * Restores from [preRestoreFile] - the verified-consistent snapshot [installDatabaseFile]
     * took moments earlier - and only falls back to renaming [movedAside] (the raw,
     * un-verified moved-aside original) back into place if copying [preRestoreFile] itself
     * also fails. ALSO-FIX 4: this is the "belt and braces" the task brief asked for.
     */
    private fun rollbackTo(preRestoreFile: File, movedAside: File, liveDb: File): RestoreResult {
        val copiedBack = runCatching { preRestoreFile.copyTo(liveDb, overwrite = true) }.isSuccess
        return if (copiedBack) {
            movedAside.delete()
            RestoreResult.Failed(
                "Couldn't install the restored database. Your original data is back in place - " +
                    "restored from the safety copy taken moments before this attempt.",
            )
        } else {
            // Even the verified-consistent copy-back failed. movedAside is the last resort and
            // is DELIBERATELY NOT DELETED here - installDatabaseFile's own re-entry guard (the
            // movedAside.exists() check above) will find it and refuse to clobber it on the
            // next attempt, and it is surfaced via listLocalRecoveries()/restoreFromLocal() so
            // it can be recovered by tapping, not by adb.
            Log.e(TAG, "rollback to preRestoreFile ALSO failed - movedAside left in place for local recovery")
            RestoreResult.Failed(
                "Couldn't install the restored database, and couldn't automatically restore your " +
                    "original data either. Nothing was deleted - check \"Recover locally\" in Drive " +
                    "sync to restore it by tapping.",
            )
        }
    }

    /**
     * Recovers [movedAsideFile] itself - the raw original database left behind by a restore
     * attempt that did not finish cleanly. Deliberately separate from [installDatabaseFile]:
     * that function's very first check refuses whenever [movedAsideFile] exists, which is
     * exactly the file THIS function's caller wants installed, so reusing it here would
     * immediately refuse against its own source file. No pre-restore safety copy is taken -
     * [movedAsideFile] IS the original, pre-restore database; whatever is currently at the
     * live path (a fresh empty database Room may have created, or a partial install) is not
     * a meaningful thing to preserve, and is not trusted as a rollback source.
     */
    private fun installInterruptedOriginal(context: Context, movedAsideFile: File): RestoreResult {
        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        val liveWal = File(liveDb.path + "-wal")
        val liveShm = File(liveDb.path + "-shm")
        val liveJournal = File(liveDb.path + "-journal")

        return CarDatabase.withDatabaseLock {
            CarDatabase.closeAndClear()
            liveWal.delete()
            liveShm.delete()
            liveJournal.delete()
            if (liveDb.exists()) liveDb.delete()

            val installed = try {
                movedAsideFile.renameTo(liveDb)
            } catch (t: Throwable) {
                Log.w(TAG, "interrupted-original install threw", t)
                false
            }

            if (!installed) {
                return@withDatabaseLock RestoreResult.Failed(
                    "Couldn't recover the interrupted restore's original database. Nothing was " +
                        "deleted - it's still available in \"Recover locally\".",
                )
            }
            RestoreResult.Ok
        }
    }

    // --------------------------------------------------------------------- local export mechanics

    /**
     * Produces a consistent local copy of the live database at [dest]. Tries `VACUUM INTO`
     * first, falls back to `PRAGMA wal_checkpoint(TRUNCATE)` + a plain file copy on ANY
     * failure - see this object's class doc comment for why the fallback is real and
     * expected, not defensive theatre. `internal` (not `private`) so tests can pin which
     * branch actually ran, per environment.
     */
    internal fun exportLocalCopy(context: Context, dest: File): ExportMethod {
        if (dest.exists()) dest.delete()
        val db = CarDatabase.getDatabase(context).openHelper.writableDatabase
        return try {
            db.execSQL("VACUUM INTO ?", arrayOf(dest.absolutePath))
            if (!dest.exists() || dest.length() == 0L) {
                throw IllegalStateException("VACUUM INTO reported success but produced no file")
            }
            ExportMethod.VACUUM_INTO
        } catch (t: Throwable) {
            Log.w(TAG, "VACUUM INTO unavailable/failed, falling back to checkpoint+copy", t)
            if (dest.exists()) dest.delete()
            checkpointAndCopy(context, db, dest)
            ExportMethod.CHECKPOINT_COPY
        }
    }

    private fun checkpointAndCopy(context: Context, db: SupportSQLiteDatabase, dest: File) {
        db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val src = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        src.copyTo(dest, overwrite = true)
    }

    private fun gzipFile(file: File): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> file.inputStream().use { it.copyTo(gz) } }
        return out.toByteArray()
    }

    private fun gunzipToFile(bytes: ByteArray, dest: File) {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz -> dest.outputStream().use { gz.copyTo(it) } }
    }

    /** SQLite's own file-header magic ("SQLite format 3 ", 16 bytes) - a cheap sanity
     * check before a downloaded (or locally-recovered) blob is ever installed as the live
     * database. */
    private fun looksLikeSqliteFile(file: File): Boolean {
        if (file.length() < 16) return false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        return String(header, Charsets.US_ASCII).startsWith("SQLite format 3")
    }

    /** Keeps only the [MAX_GENERATIONS] most recent local pre-restore safety copies -
     * these are an undo net for a single mistaken restore, not a generational archive
     * (Drive already is one), so unbounded growth here would just be wasted disk. */
    private fun prunePreRestoreBackups(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_GENERATIONS).forEach { it.delete() }
    }

    // --------------------------------------------------------------------- metadata mechanics

    /** Room's own `PRAGMA user_version` - the same number [CarDatabase]'s `@Database(version =
     * ...)` sets, read live rather than hardcoded so this file never drifts from the real
     * schema version as [CarDatabase] evolves. */
    private fun currentSchemaVersion(context: Context): Int =
        CarDatabase.getDatabase(context).openHelper.readableDatabase.version

    /**
     * Total row count across every user table (every real SQLite table except Room's own
     * bookkeeping: `room_master_table` and `sqlite_sequence`, plus the platform's
     * `android_metadata`). Deliberately reads `sqlite_master` rather than enumerating
     * [CarDatabase]'s `@Entity` list by hand, so a table added in a future migration is
     * covered automatically instead of silently excluded from the guard until someone
     * remembers to update a second list here.
     */
    private fun countUserRows(context: Context): Long {
        val db = CarDatabase.getDatabase(context).openHelper.readableDatabase
        val tables = mutableListOf<String>()
        db.query(
            "SELECT name FROM sqlite_master WHERE type='table' " +
                "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata'",
        ).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
        var total = 0L
        for (t in tables) {
            db.query("SELECT COUNT(*) FROM `$t`").use { c -> if (c.moveToFirst()) total += c.getLong(0) }
        }
        return total
    }
}
