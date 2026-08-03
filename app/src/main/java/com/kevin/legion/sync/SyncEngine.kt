package com.kevin.legion.sync

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DriveReassigner
import com.kevin.legion.vehicle.TelemetryRecorder
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DriveReassignment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private typealias Mode = SyncMerge.Mode
private typealias CompanionIdentity = CompanionSync.CompanionIdentity
private typealias CompanionClash = CompanionSync.CompanionClash

/**
 * The cross-device BYO-cloud sync engine (S1). Pushes and pulls the car-data
 * tables through the driver's own Google Drive `appDataFolder` (see [DriveClient])
 * so the head unit and phone converge on one dataset. Runs entirely on-device;
 * nothing touches a Kevin-hosted server (CLAUDE.md sec 9).
 *
 * Works at the raw-SQLite level via a per-table [Spec] registry rather than
 * per-DAO code, so adding a table is one registry line. Each table snapshot is
 * one gzipped-NDJSON file (`<table>.json.gz`); the two high-volume tables shard
 * by month (`obd_samples-YYYY-MM.json.gz`) so only the current month re-uploads.
 *
 * Merge rules (decisions.md 2026-07-14):
 *  - UNION: append-only events - a remote row is inserted locally if its identity
 *    is unseen; existing rows are never touched.
 *  - LWW: mutable rows - the copy with the newer clock column wins.
 * Identity is a natural key where one exists (vehicles/obdMac, places/label,
 * obd_samples' composite) or the portable [syncId] UUID otherwise - never the
 * local autoincrement `id`, which isn't portable across devices.
 *
 * `car_tasks` and `places` carry a `deleted` soft-delete tombstone column
 * (B19): the SELECT * snapshot below is deliberately NOT filtered on it, so a
 * tombstoned row ships to Drive and propagates through the normal LWW path
 * (a newer `deleted=1` wins like any other edit) instead of a hard DELETE
 * being invisible to sync and resurrected on the next pull. Every other
 * reader of these two tables (DAOs, controllers, tools, UI) DOES filter
 * `deleted = 0` - only this sync path and tombstone GC ever see them.
 *
 * Mixtapes are NOT synced here - they wait for the media phase (decisions.md).
 * Recaps (monthly/yearly/daily) joined in build step 5 - light data (DB rows)
 * only, cover-art bytes stay per-device (see [REGISTRY]).
 *
 * Companion identity (name/persona/traits/voice) IS synced, but as a separate
 * pass ([syncCompanion]) after the [REGISTRY] loop: it's a single `companion.json`
 * file in appDataFolder, not a Room table, and unlike the LWW tables above a
 * genuine first-time content mismatch needs a one-time driver choice rather than
 * a silent pick - see [CompanionSync.decideCompanion] and [pendingCompanionClash].
 *
 * Companion media (avatar/wallpaper) sync is currently a NO-OP:
 * [uploadCompanionMedia]/[downloadCompanionMedia] are stubs, kept as named call
 * sites in [syncCompanion] rather than deleted, because `AvatarStudio` (the
 * generator they packed/unpacked media through) was retired with the city-pop
 * design language in the 2026-07-31 pivot. Only identity fields (name/persona/
 * traits/voice) actually sync right now.
 */
object SyncEngine {
    private const val TAG = "SyncEngine"
    private val mutex = Mutex()

    // Throttle for the foreground auto-sync so returning to the app doesn't hammer
    // Drive on every resume. Manual "Sync now" bypasses this.
    private const val AUTO_SYNC_MIN_INTERVAL_MS = 5 * 60 * 1000L
    @Volatile private var lastAutoSyncAt = 0L

    // B20: cap on re-download-re-merge-retry loops when a file's upload hits a
    // live conflict (see DriveClient's class doc). A table stuck past this many
    // attempts is logged and counted a failure for this round rather than
    // looping forever - the next sync pass tries again.
    private const val MAX_CONFLICT_RETRIES = 3

    // How long a drive-reassignment rule is kept. Mirrors TelemetryRecorder's
    // TOMBSTONE_HORIZON_MS (B19) and for the same reason: the rule must outlive any
    // device that could still resurrect old-keyed rows from Drive and need re-keying.
    private const val REASSIGNMENT_HORIZON_MS = 90L * 24 * 60 * 60 * 1000

    // Legacy single-companion filenames, from before car profiles (2026-07-16).
    // Still read as a fallback so an install that synced under them keeps its
    // companion; never written any more.
    private const val LEGACY_COMPANION_FILE = "companion.json"
    private const val LEGACY_COMPANION_MEDIA_FILE = "companion_media.zip"

    // Per-car companion files. Identity is per-car now (CLAUDE.md §1: the paid
    // companion IS the car, so two cars means two companions), so each car's
    // identity and art get their own pair of files in the driver's Drive. Both
    // devices can sync at once without fighting: the head unit writes the
    // Cherokee's pair, the phone writes the Outlander's.
    private fun companionFile(context: Context): String =
        "companion-${driveSafe(ActiveVehicle.current(context))}.json"

    private fun companionMediaFile(context: Context): String =
        "companion_media-${driveSafe(ActiveVehicle.current(context))}.zip"

    // Vehicle ids can be OBD MACs (colons) or synthetic "car:<uuid>" ids. Keep
    // filenames boring - same reason AvatarStudio.sanitize exists.
    private fun driveSafe(vehicleId: String): String = vehicleId.replace(Regex("[^A-Za-z0-9]"), "_")

    // Set by syncCompanion when two devices' companion identities genuinely
    // differ and neither has ever been reconciled - a real "two companions met"
    // moment, not an ordinary edit. Non-blocking: sync itself never waits on
    // this: it just leaves both copies untouched until the driver resolves it
    // via [resolveCompanionClash], surfaced by MainActivity as a dialog.
    private val _pendingCompanionClash = MutableStateFlow<CompanionClash?>(null)
    val pendingCompanionClash: StateFlow<CompanionClash?> = _pendingCompanionClash.asStateFlow()

    /**
     * Fire-and-forget sync when the app comes to the foreground, so a mixtape or
     * note made on the phone is already here when the driver opens the app in the
     * car (and vice versa). No-op if sync isn't connected or if we synced within
     * the last few minutes. Launches on [scope]; failures are swallowed inside
     * [syncNow].
     */
    fun maybeAutoSync(context: Context, scope: CoroutineScope) {
        if (!SyncCapability.syncAvailable(context)) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSyncAt < AUTO_SYNC_MIN_INTERVAL_MS) return
        lastAutoSyncAt = now
        scope.launch { syncNow(context) }
    }

    /**
     * Sync scope owned by the engine itself, for callers that have no sensible
     * scope of their own.
     *
     * The foreground trigger used to be `maybeAutoSync(ctx, CoroutineScope(...))`
     * built fresh inside `MainActivity.onResume`, which is the exact
     * instantiate-a-scope-in-a-function-body anti-pattern
     * `.claude/skills/kotlin-coroutines-structured-concurrency` names: no owner,
     * no cancellation, a new one every resume, and no handler on the resulting
     * root Job. Handing an Activity's own scope over instead would be worse in a
     * different way - rotating the phone mid-pass would cancel a Drive upload.
     *
     * A sync pass is process work, not screen work, so the engine holds one
     * SupervisorJob scope for the process lifetime. Nothing cancels it because
     * nothing should: a pass that survives the Activity is the desired
     * behaviour, and [syncNow] is already serialised by [mutex] and bounded by
     * its own throttle.
     */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** [maybeAutoSync] on the engine's own [engineScope]. See its doc for why callers should not pass a scope. */
    fun maybeAutoSync(context: Context) = maybeAutoSync(context, engineScope)

    /**
     * @param table SQLite table name.
     * @param identity columns that identify a row across devices.
     * @param mode UNION (append-only) or LWW (mutable).
     * @param naturalPk true if the primary key IS the identity (INSERT OR REPLACE is
     *   safe); false for an autoincrement `id` (the local id is dropped on insert and
     *   rows are matched by [identity]).
     * @param clock column holding the last-write time for LWW (ignored for UNION).
     * @param hasSyncId true if [identity] is `syncId` and blank legacy values must be
     *   backfilled with a UUID before export.
     * @param shardTs a timestamp column to shard the file by month, or null for a single file.
     */
    private data class Spec(
        val table: String,
        val identity: List<String>,
        val mode: Mode,
        val naturalPk: Boolean,
        val clock: String = "updatedAt",
        val hasSyncId: Boolean = false,
        val shardTs: String? = null,
    )

    private val REGISTRY = listOf(
        // FIRST on purpose (car manager, 2026-07-16): these are the "this drive
        // belongs to a different car" rules, and they must be present locally
        // BEFORE obd_samples merges, so a rule another device made is already known
        // when that device's old-keyed rows arrive. Tiny + LWW.
        Spec("drive_reassignments", listOf("syncId"), Mode.LWW, naturalPk = false, hasSyncId = true),
        // High-volume append-only, sharded by month, natural composite identity.
        Spec("obd_samples", listOf("vehicleId", "pid", "timestamp"), Mode.UNION, naturalPk = false, shardTs = "timestamp"),
        Spec("music_plays", listOf("vehicleId", "timestamp", "title"), Mode.UNION, naturalPk = false, shardTs = "timestamp"),
        // Append-only logbook, portable syncId identity.
        Spec("memories", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("service_records", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("build_entries", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("code_events", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("oil_analyses", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        // Mutable, last-write-wins.
        Spec("car_tasks", listOf("syncId"), Mode.LWW, naturalPk = false, hasSyncId = true),
        Spec("place_reminders", listOf("syncId"), Mode.LWW, naturalPk = false, hasSyncId = true),
        Spec("places", listOf("label"), Mode.LWW, naturalPk = true, clock = "timestamp"),
        Spec("vehicles", listOf("obdMac"), Mode.LWW, naturalPk = true),
        Spec("maintenance_items", listOf("vehicleId", "serviceName"), Mode.LWW, naturalPk = true),
        Spec("vehicle_specs", listOf("vehicleId"), Mode.LWW, naturalPk = true),
        Spec("chassis_quirks", listOf("quirkId"), Mode.LWW, naturalPk = true),
        // Recaps (light-data cut): autoincrement id + a natural per-period key,
        // no syncId/updatedAt. Monthly/yearly are UNION - a finished period's
        // recap never changes after the fact, it's just present or not yet
        // present on this device.
        //
        // daily_drive_logs is the exception and MUST be LWW (changed 2026-07-16):
        // today's log is now regenerated through the day as the driver drives, so
        // under UNION the first version a device saw would be frozen there forever
        // - sync at noon and the phone would still show the noon log after an
        // evening drive. generatedAt is the clock; it advances on every rewrite,
        // so the freshest version wins on both devices.
        // coverImagePath (monthly/yearly) points at a per-device filesDir PNG
        // that does NOT travel with the row; the receiving device's cover art
        // is absent until it generates its own. LogbookScreen's recap/wrapped
        // spines already null-check the decoded bitmap and fall back to a
        // plain panel, so this is a silent, non-crashing gap by design - no
        // image bytes are ever synced (CLAUDE.md sec 8/9).
        Spec("monthly_recaps", listOf("vehicleId", "year", "month"), Mode.UNION, naturalPk = false),
        Spec("yearly_wrapped", listOf("vehicleId", "year"), Mode.UNION, naturalPk = false),
        Spec(
            "daily_drive_logs", listOf("vehicleId", "year", "month", "day"),
            Mode.LWW, naturalPk = false, clock = "generatedAt",
        ),
        // ledger_transactions is DELIBERATELY NOT REGISTERED (Kevin, 2026-08-02).
        //
        // Ticket 10 ruled it UNION on syncId, justified entirely on "transactions
        // are immutable once committed". **That premise is false.** Ticket 03/04's
        // replace flow hard-deletes a file's rows when the Drive file is replaced
        // in place - `IngestPipeline.commit` -> `deleteBySourceFileId`. UNION has
        // no delete action at all (`SyncMerge.Action` is Insert | Update), so the
        // next pass would re-download the still-present remote rows, find their
        // syncIds absent locally, re-insert them, and re-upload the resurrected
        // set. That is silently double-counted money on the one table §4's
        // reconciliation gate exists to protect, via a path the gate never sees.
        //
        // The tombstone pattern car_tasks/places use cannot rescue UNION either:
        // it works there precisely BECAUSE they are LWW, where a newer
        // `deleted = 1` wins as an ordinary edit. Under UNION an existing local
        // row is never updated, so a soft-delete would never propagate.
        //
        // So UNION and delete-propagation are mutually exclusive here, and
        // ticket 10 explicitly rejected LWW. Rather than overturn that ruling
        // inside a commit whose purpose was registration, this table waits for a
        // tombstone ticket of its own. ingested_files below still syncs, which is
        // where most of the value was: device B skips fetch AND hash for a file
        // the other device already handled.
        //
        // Found by senior-dev review of the first code ever written against
        // sync/, before any of it had run.
        // ingested_files is LWW on the NATURAL key driveFileId (ticket 03 already
        // strips the positional `acc=N;` prefix, so the stored id is identical on
        // both devices for the same file - a genuine cross-device identity with no
        // syncId column needed; ticket 10 closes that deferred question by
        // REMOVING it, not answering it). Mode is LWW, not UNION, because the
        // record is a state machine that legitimately changes (NEW -> INGESTED,
        // retry after QUARANTINED, reset on replace) - UNION would pin it to
        // whichever state propagated first. Clock is lastAttemptAt, per
        // IngestedFile's own doc comment.
        Spec("ingested_files", listOf("driveFileId"), Mode.LWW, naturalPk = true, clock = "lastAttemptAt"),
    )

    data class Result(val ok: Boolean, val message: String)

    /**
     * Runs one full sync pass. Safe to call from any dispatcher; hops to IO. Returns
     * a driver-facing [Result]. Never throws - a table that fails is logged and
     * skipped so the rest still sync.
     */
    suspend fun syncNow(context: Context): Result = withContext(Dispatchers.IO) {
        // Both of these sit INSIDE the guard now. `accessTokenOrNull` bridges a
        // Play Services Task via suspendCancellableCoroutine and resumes with an
        // EXCEPTION on a genuine Task failure - it does not merely return null.
        // It used to run outside syncNow's try/catch, so that throw escaped
        // syncNow, escaped the caller's launch{}, and reached a root Job with no
        // handler: a process crash. Harmless while the only caller was the
        // assistant service's periodic loop; not harmless now that a foreground
        // resume calls this unconditionally.
        val token = try {
            if (!SyncCapability.syncAvailable(context)) {
                return@withContext Result(false, "Cross-device sync isn't connected.")
            }
            DriveAuth.accessTokenOrNull(context)
                ?: return@withContext Result(false, "Couldn't reach your Google Drive - try again.")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't get a Drive token", e)
            MidnightEvents.recordError("sync_auth", e)
            return@withContext Result(false, "Couldn't reach your Google Drive - try again.")
        }

        mutex.withLock {
            try {
                val drive = DriveClient(token)
                val existing = drive.listAppData()
                val db = CarDatabase.getDatabase(context).openHelper.writableDatabase
                var failures = 0
                for (spec in REGISTRY) {
                    // getOrElse, not onFailure: syncTable now returns false for a
                    // conflict-exhausted skip, which throws nothing and would
                    // otherwise leave `failures` at zero and report success for a
                    // table that never uploaded.
                    val ok = runCatching { syncTable(db, drive, existing, spec) }
                        .getOrElse {
                            Log.w(TAG, "sync ${spec.table} failed", it)
                            MidnightEvents.recordError("sync_table_${spec.table}", it)
                            false
                        }
                    if (!ok) failures++
                    // Re-key immediately after the rules themselves land, so any
                    // correction another device made is applied to this device's
                    // rows before obd_samples (the very next spec) merges.
                    if (spec.table == "drive_reassignments") {
                        runCatching { applyReassignments(db) }
                            .onFailure {
                                failures++
                                Log.w(TAG, "apply reassignments (pre) failed", it)
                                MidnightEvents.recordError("sync_reassignments", it)
                            }
                    }
                }
                runCatching { syncAllCompanionIdentities(context, drive, existing) }
                    .onFailure {
                        failures++
                        Log.w(TAG, "pull companion identities failed", it)
                        MidnightEvents.recordError("sync_companion_pull", it)
                    }
                // Wired to Crashlytics (not just Log.w) so a silent failure here is
                // actually retrievable - this is the write path for companion-<id>.json,
                // and a swallowed exception here was indistinguishable in the field
                // from "sync never ran" (drive-notes-2: Fleet Hub showed "no cars
                // synced" with no way to tell whether upload failed or never fired).
                runCatching { syncCompanion(context, drive, existing) }
                    .onFailure {
                        failures++
                        Log.w(TAG, "sync companion identity failed", it)
                        MidnightEvents.recordError("sync_companion_push", it)
                    }
                if (failures == 0) Result(true, "Synced with your Google Drive.")
                else Result(false, "Synced with some issues ($failures tables skipped).")
            } catch (e: Exception) {
                // Honor this function's "never throws" contract. The setup lines
                // above (DB open, Drive list) sit outside the per-table runCatching,
                // and syncNow is launched with no CoroutineExceptionHandler from both
                // MainActivity.onResume and the SYNC NOW button - so an uncaught throw
                // here (e.g. a SQLite disk-IO error opening writableDatabase on a
                // flaky head-unit eMMC) would crash the app on foreground resume.
                Log.e(TAG, "sync failed before completing", e)
                MidnightEvents.recordError("sync_fatal", e)
                Result(false, "Sync couldn't finish - I'll try again next time.")
            }
        }
    }

    /**
     * Re-keys `obd_samples` rows per the stored corrections (car manager, 2026-07-16).
     *
     * Cheap and idempotent: a rule whose rows have already moved matches nothing, so
     * re-running it every pass costs one no-op UPDATE. That idempotence is the whole
     * design - the rule can be applied blindly wherever it's needed rather than
     * anyone tracking whether it has "been done" on this device.
     *
     * Also GCs rules past the horizon. See [com.kevin.legion.data.local.DriveReassignmentDao.purgeOlderThan]
     * for the accepted limit (a device offline longer than the horizon keeps its
     * misattribution).
     */
    private fun applyReassignments(db: SupportSQLiteDatabase) {
        val rules = mutableListOf<DriveReassignment>()
        db.query("SELECT syncId, vehicleId, fromMs, toMs, newVehicleId, updatedAt FROM drive_reassignments").use { c ->
            while (c.moveToNext()) {
                rules.add(
                    DriveReassignment(
                        syncId = c.getString(0) ?: "",
                        vehicleId = c.getString(1) ?: return@use,
                        fromMs = c.getLong(2),
                        toMs = c.getLong(3),
                        newVehicleId = c.getString(4) ?: return@use,
                        updatedAt = c.getLong(5),
                    )
                )
            }
        }
        if (rules.isEmpty()) return
        for (move in DriveReassigner.plan(rules)) {
            db.execSQL(
                "UPDATE `obd_samples` SET `vehicleId` = ? WHERE `vehicleId` = ? AND `timestamp` BETWEEN ? AND ?",
                arrayOf(move.toVehicleId, move.fromVehicleId, move.fromMs, move.toMs),
            )
        }
        db.execSQL(
            "DELETE FROM `drive_reassignments` WHERE `updatedAt` < ?",
            arrayOf<Any>(System.currentTimeMillis() - REASSIGNMENT_HORIZON_MS),
        )
    }

    /**
     * Pulls EVERY car's companion identity, not just the active one (car manager,
     * 2026-07-16).
     *
     * The eager half of Kevin's hybrid: identities are tiny JSON, so every device
     * holds every car's name/persona/voice and the CARS roster renders instantly and
     * offline. Media (megabytes of avatar faces per car) stays lazy - see
     * [ensureCompanionMedia] - so a twelve-car roster doesn't drag eleven cars'
     * portraits onto a head unit to drive one.
     *
     * Deliberately does NOT touch the ACTIVE car: [syncCompanion] owns that one,
     * including its upload and its clash-prompt path. This is a read-only fill of
     * the others, so it can never overwrite an edit the driver is making right now.
     */
    private fun syncAllCompanionIdentities(
        context: Context,
        drive: DriveClient,
        existing: Map<String, DriveClient.DriveFile>,
    ) {
        val activeFile = companionFile(context)
        for ((name, file) in existing) {
            if (!name.startsWith("companion-") || !name.endsWith(".json")) continue
            if (name == activeFile) continue // syncCompanion owns the active car
            val vehicleId = vehicleIdForCompanionFile(context, name) ?: continue
            val remote = runCatching { drive.download(file.id) }.getOrNull()
                ?.let { companionFromJson(it) } ?: continue
            val local = CompanionProfile.companionIdentityFor(context, vehicleId)
            // Plain LWW, no prompt: the clash path is a first-meeting question about
            // the car you are IN, and asking it about a car you merely have on the
            // roster would be noise the driver has no context to answer.
            if (local.isBlank || remote.updatedAt > local.updatedAt) {
                CompanionProfile.saveCompanionIdentityFor(context, vehicleId, remote)
            }
        }
    }

    /** Maps `companion-<driveSafe(id)>.json` back to a real vehicleId via the roster. */
    private fun vehicleIdForCompanionFile(context: Context, fileName: String): String? {
        val safe = fileName.removePrefix("companion-").removeSuffix(".json")
        return knownVehicleIds(context).firstOrNull { driveSafe(it) == safe }
    }

    /** Every vehicleId this device knows, archived included - the roster needs them all. */
    private fun knownVehicleIds(context: Context): List<String> {
        val ids = mutableListOf<String>()
        val db = CarDatabase.getDatabase(context).openHelper.readableDatabase
        db.query("SELECT obdMac FROM vehicles").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { ids.add(it) }
        }
        return ids
    }

    private fun syncTable(
        db: SupportSQLiteDatabase,
        drive: DriveClient,
        existing: Map<String, DriveClient.DriveFile>,
        spec: Spec,
    ): Boolean {
        if (spec.hasSyncId) backfillSyncIds(db, spec.table)
        // A sharded table reports failure if ANY shard was skipped, but still
        // attempts every other shard first - one month conflicting must not
        // silently drop the rest.
        var ok = true
        if (spec.shardTs != null) {
            for (month in monthsToSync(db, spec, existing)) {
                if (!syncFile(db, drive, existing, spec, "${spec.table}-$month.json.gz", monthFilter(spec.shardTs, month))) ok = false
            }
        } else {
            ok = syncFile(db, drive, existing, spec, "${spec.table}.json.gz", where = null)
        }
        return ok
    }

    /**
     * Merges one file's worth of rows (a whole table, or one month's shard)
     * both ways, then uploads the converged snapshot.
     *
     * B20: [DriveClient.upsert] can report [DriveClient.UpdateResult.Conflict]
     * - another device wrote this file between our download and our upload.
     * On conflict, re-list (fresh versions), re-download the now-current
     * remote, re-plan the merge against it, and retry, up to
     * [MAX_CONFLICT_RETRIES]. A table still conflicting after that throws, so
     * it's logged and counted a failure by [syncNow]'s per-table
     * `runCatching` rather than looping forever.
     */
    private fun syncFile(
        db: SupportSQLiteDatabase,
        drive: DriveClient,
        existing: Map<String, DriveClient.DriveFile>,
        spec: Spec,
        fileName: String,
        where: String?,
    ): Boolean {
        val selectSql = "SELECT * FROM `${spec.table}`${if (where != null) " WHERE $where" else ""}"
        var snapshot = existing
        var attempt = 0
        while (true) {
            attempt++
            val remote = snapshot[fileName]?.let { drive.download(it.id) }
                ?.let { SyncCodec.rowsFromGzipNdjson(it) } ?: emptyList()
            val localBefore = queryRows(db, selectSql)
            // Decide the merge with the pure planner, then execute against the DB.
            val actions = SyncMerge.plan(localBefore, remote, spec.identity, spec.mode, spec.clock)
            for (action in actions) executeAction(db, spec, action)
            // Re-key corrected drives BEFORE reading the snapshot we upload
            // (car manager, 2026-07-16). This has to happen here, not after the
            // REGISTRY loop: obd_samples is UNION, so the merge above just
            // re-inserted every old-keyed row still sitting on Drive under the car
            // we corrected away from - and `localAfter` two lines down is exactly
            // what gets uploaded. Re-keying after syncFile returned would fix this
            // device and re-upload the OLD rows anyway, so the correction would
            // resurrect on every pass, forever, on every device.
            if (spec.table == "obd_samples") applyReassignments(db)
            // Re-read local (post-merge) and upload it as the converged snapshot.
            val localAfter = queryRows(db, selectSql)
            val result = drive.upsert(fileName, SyncCodec.gzipNdjson(localAfter), snapshot)
            if (result != DriveClient.UpdateResult.Conflict) {
                if (result == DriveClient.UpdateResult.Failure) Log.w(TAG, "$fileName: upload failed")
                return true
            }
            if (!DriveConflict.shouldRetry(attempt, MAX_CONFLICT_RETRIES)) {
                // Ticket 10 (2026-08-02): this used to `check(...)`, which THREW an
                // IllegalStateException inside a sync pass nothing reports (Firebase
                // isn't wired; MidnightEvents.recordError is a Log.w wrapper). Ledger
                // is now the worst thing in the app to lose to an unreported crash,
                // so a sustained conflict logs and skips THIS FILE for this pass
                // instead - the next sync pass tries again from a fresh snapshot,
                // same as any other per-table failure syncNow's runCatching handles.
                Log.w(TAG, "$fileName: still conflicting after $attempt attempts, skipping this pass")
                MidnightEvents.recordError("sync_conflict_exhausted_$fileName", IllegalStateException("still conflicting after $attempt attempts"))
                // false, not Unit: syncNow's failure count is what decides the
                // driver-facing "Synced with your Google Drive." string, and a
                // table that silently didn't upload must not read as success.
                return false
            }
            Log.w(TAG, "$fileName: upload conflict (attempt $attempt), re-merging")
            snapshot = drive.listAppData()
        }
    }

    /**
     * Reconciles the companion identity (name/persona/traits/voice) against
     * `companion.json` in appDataFolder. Unlike [syncFile] this is plain JSON,
     * not gzip-NDJSON rows - a single object, not a table snapshot - so it's
     * downloaded/uploaded directly via [DriveClient]'s create/update/upsert
     * (its [DriveClient.DriveFile] version handling still applies; a B20
     * upload conflict here is simply left for the next sync pass to retry,
     * same as any other skipped table, rather than a second bespoke retry
     * loop).
     *
     * See [CompanionSync.decideCompanion] for the full decision matrix. PROMPT
     * publishes to [pendingCompanionClash] and returns without touching either
     * copy - [resolveCompanionClash] finishes the job once the driver picks.
     */
    private fun syncCompanion(context: Context, drive: DriveClient, existing: Map<String, DriveClient.DriveFile>) {
        // Per-car file first; fall back to the legacy single-companion file so an
        // install that already synced under it adopts its own companion rather than
        // seeing a blank remote and re-uploading a fresh one.
        val remoteFile = existing[companionFile(context)] ?: existing[LEGACY_COMPANION_FILE]
        val remote = remoteFile?.let { drive.download(it.id) }?.let { companionFromJson(it) }
        val local = CompanionProfile.companionIdentity(context)
        val reconciled = CompanionProfile.isCompanionReconciled(context)

        when (CompanionSync.decideCompanion(local, remote, reconciled)) {
            CompanionSync.Decision.UPLOAD_LOCAL -> {
                drive.upsert(companionFile(context), companionToJson(local), existing)
                CompanionProfile.markCompanionReconciled(context)
                uploadCompanionMedia(context, drive, existing)
            }
            CompanionSync.Decision.ADOPT_REMOTE -> {
                // remote is non-null on this branch (decideCompanion only returns
                // ADOPT_REMOTE when remote != null).
                CompanionProfile.saveCompanionIdentity(context, remote!!)
                CompanionProfile.markCompanionReconciled(context)
                downloadCompanionMedia(context, drive, existing)
            }
            CompanionSync.Decision.NOTHING -> CompanionProfile.markCompanionReconciled(context)
            CompanionSync.Decision.PROMPT -> {
                _pendingCompanionClash.value = CompanionClash(local, remote!!)
                // Non-blocking: leave both copies (identity AND media) as-is
                // until the driver resolves it via resolveCompanionClash.
            }
        }
    }

    /**
     * No-op for now: avatar/wallpaper generation ([AvatarStudio]) was retired with
     * the city-pop design language in the 2026-07-31 pivot, so there is no media to
     * pack and upload alongside an identity win. Kept as a named call site (rather
     * than deleted from [syncCompanion]) so a future image-gen feature has an
     * obvious place to plug back in.
     */
    private fun uploadCompanionMedia(context: Context, drive: DriveClient, existing: Map<String, DriveClient.DriveFile>) {
    }

    /**
     * No-op for now - see [uploadCompanionMedia]. Kept as a named call site for
     * the same reason.
     */
    private fun downloadCompanionMedia(context: Context, drive: DriveClient, existing: Map<String, DriveClient.DriveFile>) {
    }

    /**
     * The driver's choice on a [pendingCompanionClash]: keep this device's
     * companion ([keepLocal] true) or switch to the other device's
     * ([keepLocal] false). The winner is re-stamped with a FRESH `updatedAt`
     * (unlike [CompanionProfile.saveCompanionIdentity]'s normal adopt path) so
     * it wins the comparison on every device from here on, then written to
     * both this device and Drive, and marked reconciled so future ordinary
     * edits propagate silently by LWW instead of prompting again. The
     * matching avatar/wallpaper follow the same choice: [keepLocal] uploads
     * this device's media as the new canonical copy, `!keepLocal` downloads
     * and adopts the other device's media (see [uploadCompanionMedia] /
     * [downloadCompanionMedia] for the best-effort contract).
     */
    suspend fun resolveCompanionClash(context: Context, keepLocal: Boolean) = withContext(Dispatchers.IO) {
        val clash = _pendingCompanionClash.value ?: return@withContext
        val winner = (if (keepLocal) clash.local else clash.remote).copy(updatedAt = System.currentTimeMillis())
        CompanionProfile.saveCompanionIdentity(context, winner)
        CompanionProfile.markCompanionReconciled(context)
        val token = DriveAuth.accessTokenOrNull(context)
        if (token != null) {
            mutex.withLock {
                val drive = DriveClient(token)
                val existing = drive.listAppData()
                drive.upsert(companionFile(context), companionToJson(winner), existing)
                if (keepLocal) uploadCompanionMedia(context, drive, existing)
                else downloadCompanionMedia(context, drive, existing)
            }
        }
        _pendingCompanionClash.value = null
    }

    private fun companionToJson(id: CompanionIdentity): ByteArray {
        val o = JSONObject()
            .put("name", id.name)
            .put("persona", id.persona)
            .put("traits", id.traits)
            .put("voice", id.voice)
            .put("updatedAt", id.updatedAt)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    private fun companionFromJson(bytes: ByteArray): CompanionIdentity? =
        try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            CompanionIdentity(
                name = o.optString("name", ""),
                persona = o.optString("persona", ""),
                traits = o.optString("traits", ""),
                voice = o.optString("voice", ""),
                updatedAt = o.optLong("updatedAt", 0L),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "companion.json parse failed", t)
            null
        }

    private fun executeAction(db: SupportSQLiteDatabase, spec: Spec, action: SyncMerge.Action) {
        when (action) {
            is SyncMerge.Action.Insert ->
                insertRow(db, spec.table, action.row, omit = if (spec.naturalPk) emptySet() else setOf("id"))
            is SyncMerge.Action.Update -> {
                val localId = findLocalRowId(db, spec, action.row) ?: return
                updateRow(db, spec.table, action.row, keyCol = if (spec.naturalPk) null else "id", keyVal = localId)
            }
        }
    }

    /** The identity (or PK) rowid of a matching local row, or null. */
    private fun findLocalRowId(db: SupportSQLiteDatabase, spec: Spec, row: JSONObject): Long? {
        val idCol = if (spec.naturalPk) "rowid" else "id"
        val where = spec.identity.joinToString(" AND ") { "`$it`=?" }
        val args = spec.identity.map { SyncCodec.sqlArg(row, it) }.toTypedArray()
        db.query("SELECT `$idCol` FROM `${spec.table}` WHERE $where LIMIT 1", args).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    private fun insertRow(db: SupportSQLiteDatabase, table: String, row: JSONObject, omit: Set<String>) {
        val cols = row.keys().asSequence().filter { it !in omit }.toList()
        if (cols.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `$table` (${cols.joinToString(",") { "`$it`" }}) " +
            "VALUES (${cols.joinToString(",") { "?" }})"
        db.execSQL(sql, cols.map { SyncCodec.sqlArg(row, it) }.toTypedArray())
    }

    private fun updateRow(db: SupportSQLiteDatabase, table: String, row: JSONObject, keyCol: String?, keyVal: Long) {
        val col = keyCol ?: "rowid"
        val cols = row.keys().asSequence().filter { it != "id" }.toList()
        if (cols.isEmpty()) return
        val sql = "UPDATE `$table` SET ${cols.joinToString(",") { "`$it`=?" }} WHERE `$col`=?"
        db.execSQL(sql, (cols.map { SyncCodec.sqlArg(row, it) } + keyVal).toTypedArray())
    }

    /** Gives every blank-syncId row a stable UUID so it can be identified across devices. */
    private fun backfillSyncIds(db: SupportSQLiteDatabase, table: String) {
        db.query("SELECT `id` FROM `$table` WHERE `syncId` = '' OR `syncId` IS NULL").use { c ->
            val ids = ArrayList<Long>()
            while (c.moveToNext()) ids.add(c.getLong(0))
            for (id in ids) {
                db.execSQL("UPDATE `$table` SET `syncId`=? WHERE `id`=?", arrayOf<Any?>(UUID.randomUUID().toString(), id))
            }
        }
    }

    private fun monthsToSync(db: SupportSQLiteDatabase, spec: Spec, existing: Map<String, DriveClient.DriveFile>): Set<String> {
        val months = linkedSetOf<String>()
        // Months present in local data.
        db.query("SELECT DISTINCT strftime('%Y-%m', `${spec.shardTs}`/1000, 'unixepoch') FROM `${spec.table}`").use { c ->
            while (c.moveToNext()) c.getString(0)?.let { months.add(it) }
        }
        // Months that exist as a remote shard file (so we still pull a month we have no local rows for).
        val prefix = "${spec.table}-"
        for (name in existing.keys) {
            if (name.startsWith(prefix) && name.endsWith(".json.gz")) {
                months.add(name.removePrefix(prefix).removeSuffix(".json.gz"))
            }
        }
        // Drop months the device has deliberately purged (2026-07-16 fix).
        //
        // The remote-shard branch above exists so a FRESH device pulls history it
        // has no rows for. But it cannot tell "fresh device, pull everything" from
        // "we purged this on purpose, leave it alone" - so every sync re-inserted
        // every row TelemetryRecorder's 365-day retention had just deleted, and
        // re-uploaded them. The purge was undone on every pass, obd_samples grew
        // without bound on the head unit's eMMC AND on Drive, and the purge only
        // ever burned CPU.
        //
        // Kevin's call (2026-07-16): the DEVICE keeps RETENTION_MS; the driver's
        // Drive keeps everything as a permanent archive. Old months stay on Drive
        // and simply stop being pulled back, so §1's compounding-history moat
        // survives while the constrained disk stays bounded. Anything older than
        // the window is Drive-only and needs an explicit archive fetch.
        return months.filterTo(linkedSetOf()) { it >= retentionFloorMonth() }
    }

    /**
     * The oldest month the device still keeps locally, as `YYYY-MM`. Compared
     * lexically against the shard month, which is exact for this format.
     *
     * Deliberately floors to the START of the retention month rather than the day:
     * a shard is a whole month, so a partially-retained month must sync in full or
     * its still-live rows would be stranded.
     */
    private fun retentionFloorMonth(): String {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis() - TelemetryRecorder.RETENTION_MS
        }
        return "%04d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
    }

    private fun monthFilter(tsCol: String, month: String): String =
        "strftime('%Y-%m', `$tsCol`/1000, 'unixepoch') = '$month'"

    private fun queryRows(db: SupportSQLiteDatabase, sql: String): List<JSONObject> =
        db.query(sql).use { cursorToRows(it) }

    /** Android [Cursor] -> row JSON. Stays here (SyncCodec is Android-free). */
    private fun cursorToRows(c: Cursor): List<JSONObject> {
        val rows = ArrayList<JSONObject>(c.count)
        while (c.moveToNext()) {
            val o = JSONObject()
            for (i in 0 until c.columnCount) {
                val name = c.getColumnName(i)
                when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> o.put(name, JSONObject.NULL)
                    Cursor.FIELD_TYPE_INTEGER -> o.put(name, c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> o.put(name, c.getDouble(i))
                    else -> o.put(name, c.getString(i))
                }
            }
            rows.add(o)
        }
        return rows
    }
}
