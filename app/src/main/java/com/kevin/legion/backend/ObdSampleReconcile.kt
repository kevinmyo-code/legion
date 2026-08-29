package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.engine.fleet.FleetRecordBridge

/**
 * Install-scoped high-water mark for [ObdSampleReconcile]'s upload - the local [OdbSample.id] of
 * the last row this device has successfully handed to Postgres (uploaded OR permanently found to
 * be past the point [ObdSampleReconcile] halted at - see that object's own class doc for why a
 * halt, not a skip-and-continue, is the chosen shape). Plain [android.content.SharedPreferences],
 * same install-scoped posture as [SupabaseConfig] - this is bookkeeping for ONE device's own
 * upload progress, never a value that needs to survive a reinstall or be read by the other phone.
 *
 * **Why a checkpoint at all, when the natural key already makes a re-upload a free no-op.**
 * Idempotency and cheapness are different properties. Re-scanning and re-posting all 26,000+ rows
 * on every run would still be CORRECT (Postgres just ignores every duplicate), but it is not
 * CHEAP - the ticket brief's own instruction. This cursor is what makes a routine re-run touch
 * only the rows that arrived since the last one, at the cost of nothing more than a `SharedPreferences`
 * read.
 */
internal object ObdSampleUploadCursor {
    private const val PREFS = "obd_sample_upload_cursor"
    private const val KEY_LAST_ID = "last_uploaded_local_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last local id this device has advanced past, or 0 (before the table's first row) if
     *  this device has never run a successful upload. */
    fun lastUploadedId(context: Context): Long = prefs(context).getLong(KEY_LAST_ID, 0L)

    /** Persisted after every successfully-uploaded batch, not just once at the end of [ObdSampleReconcile.run] -
     *  so a run interrupted mid-way (network drop, app killed) resumes from its last real progress
     *  on the next attempt rather than re-uploading everything already sent. */
    fun advance(context: Context, id: Long) {
        prefs(context).edit().putLong(KEY_LAST_ID, id).apply()
    }
}

/**
 * The upload path for `obd_samples` - the last of fleet's tables to reach the server
 * (`.scratch/backend-erp/issues/14-a-vehicle-row-is-co-owned.md`'s "obd_samples question", RULED
 * 2026-08-29: "obd samples also goes to supabase"). Schema:
 * `supabase/migrations/20260829000100_obd_samples_and_conversation_audit.sql`, UNAPPLIED as of
 * that migration's own header - no CLI or project credentials in this environment, and nothing in
 * this object has run on device.
 *
 * **A sibling to [FleetReconcile], not a thirteenth wave inside it, and the reason is volume, not
 * taste.** Every wave in [FleetReconcile] reads its ENTIRE local table, diffs it whole against
 * every active server row, and reports an `onlyOnSource`/`onlyOnServer` symmetric difference - a
 * shape that is exactly right for a few thousand rows at most and actively wrong here: 26,059 rows
 * on Kevin's device 2026-08-29, growing ~600/day
 * (`.scratch/backend-erp/issues/14-*.md`'s own measurement), so a naive row-at-a-time upload or a
 * whole-table diff on every run is neither. This object instead:
 *
 * 1. **Batches.** [BATCH_SIZE] rows per [FleetBackend.uploadObdSampleBatch] call - 500, chosen as
 *    the point where a request is still comfortably small on a phone connection (roughly 500 *
 *    ~150 bytes of JSON per row, under 100 KB) while keeping a full 26k-row backfill to about 52
 *    requests rather than 26,059.
 * 2. **Resumes**, via [ObdSampleUploadCursor] - a local id high-water mark advanced after every
 *    successfully-uploaded batch, so a routine re-run (the expected steady state: a handful of new
 *    rows a day) touches only what arrived since the last run rather than re-scanning and
 *    re-posting the whole table. The natural key ([ObdSampleUpload]'s own doc: `(vehicle_id, pid,
 *    recorded_at)`, `on conflict do nothing`) makes an unnecessary re-post CORRECT; the cursor is
 *    what makes it additionally CHEAP, which are different properties (see
 *    [ObdSampleUploadCursor]'s own doc).
 * 3. **Does not attempt a symmetric diff at all.** There is no `onlyOnServer`/`onlyOnSource`
 *    reconciliation here - fetching all 26k+ server rows back just to compare would defeat the
 *    entire batching effort for a report line, and the natural key means "is this row on the
 *    server" is never actually in doubt: either this run's cursor has passed it (and it is,
 *    barring a genuine transport failure this run already surfaced), or it has not (and
 *    [Report.uploaded] already says how many rows this run sent).
 *
 * **Vehicle resolution reuses [FleetReconcile]'s exact mechanism, not a copy of its logic.** A
 * [com.kevin.legion.data.local.OdbSample.vehicleId] is [com.kevin.legion.data.local.Vehicle.obdMac],
 * the same legacy identity every other fleet upload resolves through
 * [FleetRecordBridge.vehicleGuid] and [FleetBackend.fetchActiveVehicles]'s `origin_guid` - this
 * object builds that map itself (a handful of local vehicles, cheap to scan) rather than taking it
 * as a parameter, so it can be run entirely independently of [FleetReconcile] having run first in
 * the SAME process, while still depending on it having run EVENTUALLY (a vehicle must exist
 * server-side before its samples can).
 *
 * **An unresolved vehicle HALTS the run at that point, rather than being skipped and the scan
 * continuing past it.** This is the one real tradeoff of the batching/cursor design, stated
 * plainly rather than left implicit: [FleetReconcile]'s waves re-scan their WHOLE table every run,
 * so a row skipped for "vehicle not yet migrated" is retried for free on the next run once the
 * vehicle appears. A cursor-based scan cannot offer that for free - advancing the cursor PAST an
 * unresolved sample would mean it is never retried again, silently. Halting instead means the
 * cursor never advances past a point this run could not honestly resolve, so the exact same
 * samples are retried, in order, the next time this reconcile runs - at the cost of also holding
 * back any LATER, already-resolvable samples from a different vehicle that happen to sit after it
 * in insertion order. In practice this is a non-issue: `TelemetryRecorder` only ever writes
 * samples for a vehicle already registered locally, so the ordering that matters is "run Fleet's
 * vehicle upload before this", not "upload vehicles and drive at the same time" - but it is a real
 * ordering dependency and this paragraph is where it is written down.
 */
object ObdSampleReconcile {
    private const val BATCH_SIZE = 500

    /**
     * @param sourceCount every `obd_samples` row on this device, regardless of upload state - the
     *   "how big is this table" figure, not "how many are left".
     * @param uploaded rows this RUN sent in a batch that [FleetBackend.uploadObdSampleBatch]
     *   accepted - counted by batch size attempted, matching [DriveReport.uploaded]'s own "a
     *   repost still counts" convention (see [FleetBackend.uploadObdSampleBatch]'s own doc for why
     *   there is no cheaper way to learn "how many were genuinely new" than that convention).
     * @param skippedUnresolvedVehicle named per-vehicle, not per-sample - a stalled vehicle can
     *   account for thousands of samples, and naming each one would make the report itself the
     *   next performance problem. Empty on the (expected, steady-state) run where every vehicle
     *   with samples has already been uploaded by [FleetReconcile].
     * @param cursorAt the local id this run's upload has now reached - exposed for the report
     *   words and for tests, mirrors [ObdSampleUploadCursor.lastUploadedId] after this call.
     */
    data class Report(
        val sourceCount: Int,
        val uploaded: Int,
        val skippedUnresolvedVehicle: List<String>,
        val cursorAt: Long,
    )

    suspend fun run(context: Context, backend: FleetBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)

        // Same obdMac -> guid -> server-uuid chain FleetReconcile's own class doc describes -
        // built independently here (see this object's own class doc) rather than shared, since the
        // two objects are never required to run in the same process invocation.
        val vehicles = db.vehicleDao().getAllIncludingArchived()
        val guidByObdMac = vehicles.associate { it.obdMac to FleetRecordBridge.vehicleGuid(it.obdMac) }
        val serverVehicles = backend.fetchActiveVehicles().getOrElse { return Result.failure(it) }
        val serverIdByOriginGuid = serverVehicles.mapNotNull { row -> row.originGuid?.let { it to row.serverId } }.toMap()

        val sourceCount = db.odbSampleDao().totalCountAll()
        var cursor = ObdSampleUploadCursor.lastUploadedId(context)
        var uploadedThisRun = 0
        val skippedUnresolvedVehicle = mutableListOf<String>()

        run@ while (true) {
            val batch = db.odbSampleDao().getAfterId(cursor, BATCH_SIZE)
            if (batch.isEmpty()) break@run

            val uploads = mutableListOf<ObdSampleUpload>()
            var batchCursor = cursor
            var stuckVehicle: String? = null

            for (sample in batch) {
                val vehicleServerId = guidByObdMac[sample.vehicleId]?.let { serverIdByOriginGuid[it] }
                if (vehicleServerId == null) {
                    // Halt right here - see this object's own class doc for why this is a halt,
                    // not a skip-and-continue.
                    stuckVehicle = sample.vehicleId
                    break
                }
                uploads.add(
                    ObdSampleUpload(
                        vehicleServerId = vehicleServerId,
                        pid = sample.pid,
                        value = sample.value,
                        unit = sample.unit,
                        recordedAtMs = sample.timestamp,
                        lat = sample.lat,
                        lng = sample.lng,
                    ),
                )
                batchCursor = sample.id
            }

            if (uploads.isNotEmpty()) {
                backend.uploadObdSampleBatch(uploads).getOrElse { return Result.failure(it) }
                uploadedThisRun += uploads.size
                cursor = batchCursor
                ObdSampleUploadCursor.advance(context, cursor)
            }

            if (stuckVehicle != null) {
                skippedUnresolvedVehicle.add("$stuckVehicle: vehicle not yet migrated - run Fleet's vehicle upload first")
                break@run
            }
        }

        return Result.success(
            Report(
                sourceCount = sourceCount,
                uploaded = uploadedThisRun,
                skippedUnresolvedVehicle = skippedUnresolvedVehicle,
                cursorAt = cursor,
            ),
        )
    }
}
