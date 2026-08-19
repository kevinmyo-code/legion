package com.kevin.legion.sync

import android.content.Context
import android.database.Cursor
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kevin.legion.MidnightEvents
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.vehicle.DriveReassigner
import com.kevin.legion.vehicle.TelemetryRecorder
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.ui.sync.GoogleGrantResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private typealias Mode = SyncMerge.Mode

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
 * **Companion identity sync (Kevin, 2026-08-02: named, synced companion
 * profiles).** `companion_profiles` is a normal [REGISTRY] entry now - LWW on
 * the portable `profileId` UUID, same as any other natural-key table. This
 * retires the entire bespoke single-identity path that used to live here:
 * `syncCompanion`, the per-car `companion-<vehicleId>.json` file, and
 * `CompanionSync.decideCompanion`'s "two companions met" clash prompt
 * (`pendingCompanionClash`/`resolveCompanionClash`). That machinery existed
 * to reconcile ONE identity across devices; with named profiles two rows
 * simply coexist through the ordinary LWW merge, so there is nothing left to
 * clash over. After every [REGISTRY] table (including `companion_profiles`)
 * has merged, [syncNow] materialises the ACTIVE profile's fields into
 * [com.kevin.legion.ai.CompanionProfile]'s legacy flat keys via
 * [CompanionProfileStore.materializeActive], so a profile edited on the
 * other device shows up here without every reader of `CompanionProfile`
 * needing to change. See [com.kevin.legion.data.local.CompanionProfileEntity]'s
 * doc comment for the full design.
 *
 * Companion MEDIA (avatar/wallpaper) sync was already a no-op before this
 * change - `AvatarStudio` (the generator media synced through) was retired
 * with the city-pop design language in the 2026-07-31 pivot - so it is not
 * reintroduced here either.
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
        // music_plays was registered here until 2026-08-03. The table was dropped in
        // the v1 port with the rest of the music-taste ledger (CLAUDE.md sec 5), so
        // every sync pass threw `no such table: music_plays` from monthsToSync and
        // counted a failure. Nothing caught it because sync/ had never actually run
        // on a device - it was structurally unreachable until 7ea4725.
        // Append-only logbook, portable syncId identity.
        Spec("memories", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("service_records", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("build_entries", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        Spec("code_events", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        // code_clear_events (D3, `.scratch/hands-and-senses/issues/01-clear-dtc.md`): append-only
        // falsifiable facts about the car, same posture as code_events one line up - no
        // `deleted` tombstone, UNION on the portable syncId.
        Spec("code_clear_events", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
        // drives (`.scratch/drive-ui/issues/05-trip-content.md` Q14): the drive-boundary object -
        // append-only falsifiable facts about the car, same posture as code_events/code_clear_events
        // two lines up. A finalised drive never changes after the fact, so UNION on the portable
        // syncId, no `deleted` tombstone.
        Spec("drives", listOf("syncId"), Mode.UNION, naturalPk = false, hasSyncId = true),
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
        // Named companion profiles (Kevin, 2026-08-02). LWW on the portable
        // profileId UUID - a profile is edited over time (renamed, re-
        // personified, a new voice picked) and the newer edit should win, the
        // same shape as vehicles/maintenance_items above. Replaces the
        // bespoke single-identity companion.json sync entirely - see this
        // object's class doc comment.
        Spec("companion_profiles", listOf("profileId"), Mode.LWW, naturalPk = true, clock = "updatedAt"),
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
            // tokenOrReason, not accessTokenOrNull: a lapsed/revoked grant (TokenResult.NeedsConsent)
            // used to collapse into the exact same null as a real error, so a driver whose Drive
            // access had been revoked read the identical "couldn't reach your Google Drive - try
            // again" as someone briefly offline - ticket 06 point 4's live defect. NeedsConsent now
            // says so by name; a genuine Failed still routes through GoogleGrantResolver.diagnose so
            // a DEVELOPER_ERROR reads as a config problem rather than a generic failure either.
            when (val outcome = DriveAuth.tokenOrReason(context)) {
                is DriveAuth.TokenResult.Token -> outcome.accessToken
                DriveAuth.TokenResult.NeedsConsent ->
                    return@withContext Result(
                        false,
                        GoogleGrantResolver.needsReauthorisingMessage(GoogleGrantResolver.Grant.DRIVE),
                    )
                is DriveAuth.TokenResult.Failed -> {
                    val failure = GoogleGrantResolver.diagnose(
                        grant = GoogleGrantResolver.Grant.DRIVE,
                        statusCode = DriveAuth.statusCodeOf(outcome.error),
                        isNetworkException = DriveAuth.looksLikeNetworkFailure(outcome.error),
                        fallbackMessage = outcome.error.message,
                    )
                    Log.w(TAG, "couldn't get a Drive token", outcome.error)
                    MidnightEvents.recordError("sync_auth", outcome.error)
                    return@withContext Result(false, failure.message)
                }
            }
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
                // companion_profiles has already merged as an ordinary REGISTRY
                // table by this point (LWW, same as vehicles/maintenance_items).
                // What's left is device-local: re-derive CompanionProfile's flat
                // keys from whichever profile THIS device has active, in case the
                // active profile's row just changed underneath it (an edit made on
                // the other phone). See CompanionProfileStore's doc comment for
                // why this is a materialisation step and not a sync step.
                runCatching { CompanionProfileStore.materializeActive(context) }
                    .onFailure {
                        failures++
                        Log.w(TAG, "materialize active companion profile failed", it)
                        MidnightEvents.recordError("sync_companion_materialize", it)
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

    /**
     * The column names this device's schema actually has for [table], cached for
     * the process lifetime (the schema cannot change without a migration, which
     * runs before any sync pass).
     *
     * Exists because a payload's columns and the local schema's columns are NOT
     * the same set and never were. A row can arrive from an older app version, a
     * newer one, or - as on 2026-08-03 - from the Midnight AI import assets, whose
     * `build_entries` rows still carry the `photoPath` column that the v1 port
     * dropped. [insertRow] built its INSERT from the payload's own keys, so every
     * such row threw `table build_entries has no column named photoPath` and the
     * whole table imported as zero rows.
     *
     * Stripping `photoPath` from the seed assets would have fixed that one case
     * and left the next dropped column to do it again. Intersecting here makes an
     * unknown column degrade instead of throw, which is what a sync layer that
     * spans app versions has to do.
     */
    private val columnCache = ConcurrentHashMap<String, Set<String>>()

    /** `table.column` pairs already reported, so an 11k-row import logs once, not 11k times. */
    private val reportedDrops = ConcurrentHashMap.newKeySet<String>()

    private fun knownColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
        columnCache.getOrPut(table) {
            val cols = LinkedHashSet<String>()
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                while (c.moveToNext()) c.getString(nameIdx)?.let { cols.add(it) }
            }
            cols
        }

    /**
     * Payload columns this schema has, in payload order. Anything the local table
     * does not define is dropped and reported - silently discarding a column we
     * were sent is the same sin as writing one we can't verify (CLAUDE.md sec 4
     * rule 6), so it goes through [MidnightEvents.syncColumnsDropped] rather than
     * vanishing.
     */
    private fun writableColumns(
        db: SupportSQLiteDatabase,
        table: String,
        row: JSONObject,
        omit: Set<String>,
    ): List<String> {
        val known = knownColumns(db, table)
        val candidates = row.keys().asSequence().filter { it !in omit }.toList()
        val usable = candidates.filter { it in known }
        val dropped = candidates.filter { it !in known && reportedDrops.add("$table.$it") }
        if (dropped.isNotEmpty()) MidnightEvents.syncColumnsDropped(table, dropped)
        return usable
    }

    private fun insertRow(db: SupportSQLiteDatabase, table: String, row: JSONObject, omit: Set<String>) {
        val cols = writableColumns(db, table, row, omit)
        if (cols.isEmpty()) return
        val sql = "INSERT OR REPLACE INTO `$table` (${cols.joinToString(",") { "`$it`" }}) " +
            "VALUES (${cols.joinToString(",") { "?" }})"
        db.execSQL(sql, cols.map { SyncCodec.sqlArg(row, it) }.toTypedArray())
    }

    private fun updateRow(db: SupportSQLiteDatabase, table: String, row: JSONObject, keyCol: String?, keyVal: Long) {
        val col = keyCol ?: "rowid"
        val cols = writableColumns(db, table, row, omit = setOf("id"))
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
