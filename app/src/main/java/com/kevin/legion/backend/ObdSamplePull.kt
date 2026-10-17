package com.kevin.legion.backend

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OdbSample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the Telemetry screen says about how fresh its engine-pulled data is. Install-scoped
 * [android.content.SharedPreferences], beside [FleetPullCursor] - bookkeeping for ONE phone.
 *
 * **Why a status at all (CLAUDE.md section 7, "the phone reads from its Room replica when the
 * server is down ... and says so in words").** The screen reads Room whether or not the engine was
 * reachable, so without this line a chart that is a day behind looks identical to one that is
 * current. A failure is kept until the next success and is shown WITH the last success time, so
 * "could not reach the engine" is never read as "there is nothing new".
 */
internal object ObdPullStatus {
    private const val PREFS = "obd_pull_status"
    private const val KEY_OK_AT = "last_success_ms"
    private const val KEY_FAIL_AT = "last_failure_ms"
    private const val KEY_FAIL_MSG = "last_failure_message"
    private const val KEY_PARTIAL = "history_still_downloading"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordSuccess(context: Context, atMs: Long, moreToFetch: Boolean) {
        prefs(context).edit()
            .putLong(KEY_OK_AT, atMs)
            .putBoolean(KEY_PARTIAL, moreToFetch)
            .remove(KEY_FAIL_AT)
            .remove(KEY_FAIL_MSG)
            .apply()
    }

    fun recordFailure(context: Context, atMs: Long, message: String) {
        prefs(context).edit().putLong(KEY_FAIL_AT, atMs).putString(KEY_FAIL_MSG, message).apply()
    }

    /** The one line the screen shows. Pure over its arguments so a test can pin the wording. */
    fun describe(okAtMs: Long?, failureMessage: String?, moreToFetch: Boolean): String {
        val at = okAtMs?.let { formatTime(it) }
        val partial = if (moreToFetch) " Older history is still downloading." else ""
        return when {
            failureMessage != null && at != null ->
                "Could not update from the engine: $failureMessage Showing what this phone has. " +
                    "Last updated from engine $at.$partial"
            failureMessage != null ->
                "Could not update from the engine: $failureMessage Showing what this phone has; it has " +
                    "never been updated from the engine."
            at != null -> "Updated from engine $at.$partial"
            else -> "Not updated from the engine yet. Showing what this phone has."
        }
    }

    fun line(context: Context): String {
        val p = prefs(context)
        val ok = if (p.contains(KEY_OK_AT)) p.getLong(KEY_OK_AT, 0L) else null
        return describe(ok, p.getString(KEY_FAIL_MSG, null), p.getBoolean(KEY_PARTIAL, false))
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ms))
}

/**
 * The DOWNLOAD half of `obd_samples`: samples recorded by ANY device (the head unit's OBD dongle is
 * the case that motivated it, Kevin 2026-10-17) are pulled from the household engine into this
 * phone's Room table, and the Telemetry screen keeps reading Room. [ObdSampleReconcile] is the
 * upload half and is unchanged apart from reading [ObdSampleUploadCursor] this object advances.
 *
 * **One pull per engine vehicle, in pages, cursor advanced only after a page is stored.** A page is
 * written in one Room transaction and only then does [FleetPullCursor] move, so a failure on page
 * 40 keeps pages 1-39 and resumes from them. The engine's `since` is inclusive and the cursor is
 * the newest `recorded_at` stored, so the boundary instant is re-served every time; the dedupe
 * below is what makes that correct.
 *
 * **Dedupe is read-then-skip, not INSERT-IGNORE.** `obd_samples` has a composite index on
 * `(vehicleId, pid, timestamp)` but it is NOT unique, and making it unique would be a Room
 * migration on the app's largest table (CLAUDE.md section 5: needs a ruling). The read is scoped to
 * the page's own time span, so it costs one indexed range query per PID per page.
 *
 * **Re-upload prevention.** [ObdSampleReconcile]'s cursor is a local-id high-water mark, and a
 * pulled row gets a NEW local id above it, so without care every pulled row would be re-posted (an
 * idempotent no-op server-side, but tens of thousands of wasted requests after a backfill). The
 * page's inserts are bracketed by [com.kevin.legion.data.local.OdbSampleDao.maxId] inside the
 * transaction; afterwards, if the upload cursor had already reached the top of the table before
 * the insert, it is advanced to the top of the pulled rows. If the phone has genuinely unsent local
 * rows (cursor behind the top) the cursor is left alone, because moving it would skip them - those
 * pulled rows are then re-posted once, harmlessly, and counted in [Report.repostPending].
 */
object ObdSamplePull {
    /**
     * First-run backfill bound: one year, the longest FINITE range the Telemetry screen offers
     * ([com.kevin.legion.ui.fleet.TelemetryRange.YEAR]; its `ALL` has no bound and is exactly the
     * whole-history download this must not be). It also equals
     * [com.kevin.legion.vehicle.TelemetryRecorder.RETENTION_MS], so the pull never fetches rows
     * the phone's own retention sweep would delete on the next service start - both are asserted
     * equal in `ObdSamplePullTest`. This WIDENS Kevin's 2026-09-03 "roughly the last 30 days"
     * ruling at Kevin's own 2026-10-17 request, so that a head unit's history is visible in every
     * range the screen offers.
     */
    const val BACKFILL_WINDOW_DAYS = 365L
    internal const val BACKFILL_WINDOW_MS = BACKFILL_WINDOW_DAYS * 24 * 60 * 60 * 1000L

    /**
     * Each run re-asks from this far BEFORE the cursor. `since` filters `recorded_at` (when the
     * reading was taken), not when the engine received it, so a head unit that uploads a drive a
     * few hours late would otherwise land BEHIND the cursor and never be fetched. The engine has no
     * `created_at` filter, so overlap plus dedupe is the cheapest honest mitigation; a head unit
     * offline for longer than this can still be missed.
     */
    internal const val OVERLAP_MS = 24 * 60 * 60 * 1000L

    /** Pages per vehicle per run (500 rows each): a year of backfill resumes over several runs
     *  rather than holding the foreground for one enormous one. Hitting it is reported, not hidden. */
    internal const val MAX_PAGES_PER_VEHICLE = 100

    internal const val CURSOR_PREFIX = "obd_samples_pull:"

    private val runLock = Mutex()

    /**
     * @param inserted rows newly stored this run.
     * @param skippedVehicles one sentence per engine vehicle with no local twin, never auto-created.
     * @param failures one sentence per vehicle (or the whole run) that did not complete.
     * @param moreToFetch a vehicle hit [MAX_PAGES_PER_VEHICLE]; the next run continues.
     * @param repostPending pulled rows the upload will re-post once because unsent local rows
     *   stopped the upload cursor from being moved past them.
     */
    data class Report(
        val inserted: Int,
        val skippedVehicles: List<String>,
        val failures: List<String>,
        val moreToFetch: Boolean,
        val repostPending: Int,
    ) {
        val succeeded: Boolean get() = failures.isEmpty()
    }

    /**
     * Pulls every vehicle in [engineVehicleIds] that has a local twin in [obdMacByServerId] (the
     * `vehicle_sidecar` map [FleetSync] already uses - not a second mapping). Records
     * [ObdPullStatus]. Never throws for a transport failure: Room is left exactly as it was and the
     * report says so.
     */
    suspend fun pull(
        context: Context,
        backend: FleetBackend,
        engineVehicleIds: Collection<String>,
        obdMacByServerId: Map<String, String>,
        nowMs: Long = System.currentTimeMillis(),
    ): Report = runLock.withLock {
        val app = context.applicationContext
        val db = CarDatabase.getDatabase(app)
        var inserted = 0
        var repost = 0
        var more = false
        val skipped = mutableListOf<String>()
        val failures = mutableListOf<String>()

        for (serverId in engineVehicleIds) {
            val mac = obdMacByServerId[serverId]
            if (mac == null || db.vehicleDao().getByMac(mac) == null) {
                val why = "Engine vehicle $serverId has no matching car on this phone, so its telemetry " +
                    "was not downloaded. The next fleet sync creates the car."
                skipped.add(why)
                MidnightEvents.obdPullSkippedVehicle(why)
                continue
            }
            val outcome = pullVehicle(app, db, backend, serverId, mac, nowMs)
            inserted += outcome.inserted
            repost += outcome.repostPending
            more = more || outcome.moreToFetch
            outcome.failure?.let { failures.add(it) }
        }

        if (failures.isEmpty()) {
            ObdPullStatus.recordSuccess(app, nowMs, more)
        } else {
            ObdPullStatus.recordFailure(app, nowMs, failures.first())
        }
        Report(inserted, skipped, failures, more, repost)
    }

    /**
     * The Telemetry screen's entry point (open + the refresh button): builds the fleet backend,
     * sends this phone's own unsent samples first so the upload cursor is at the top of the table
     * when the pull inserts, asks the engine which vehicles exist, and pulls. Bypasses
     * [FleetSync]'s five-minute foreground throttle on purpose - a person asked.
     */
    suspend fun refresh(context: Context): Report {
        val app = context.applicationContext
        val backends = EngineBackends(app)
        val backend = if (backends.isConfiguredFor(EngineBackends.ASPECT_FLEET)) backends.fleetBackend() else null
        if (backend == null) return notAvailable(app, "No engine is set up on this phone.")

        runCatching { ObdSampleReconcile.run(app, backend) }

        val engineVehicles = backend.fetchActiveVehicles().getOrElse {
            return notAvailable(app, it.message ?: "The engine could not be reached.")
        }
        val db = CarDatabase.getDatabase(app)
        val macs = db.vehicleSidecarDao().getAll().associate { it.serverId to it.obdMac }
        return pull(app, backend, engineVehicles.map { it.serverId }, macs)
    }

    private fun notAvailable(app: Context, message: String): Report {
        ObdPullStatus.recordFailure(app, System.currentTimeMillis(), message)
        return Report(0, emptyList(), listOf(message), moreToFetch = false, repostPending = 0)
    }

    private class VehicleOutcome(
        val inserted: Int,
        val repostPending: Int,
        val moreToFetch: Boolean,
        val failure: String?,
    )

    private suspend fun pullVehicle(
        app: Context,
        db: CarDatabase,
        backend: FleetBackend,
        serverId: String,
        mac: String,
        nowMs: Long,
    ): VehicleOutcome {
        val key = CURSOR_PREFIX + serverId
        var cursor = FleetPullCursor.lastPulledAtMs(app, key)
        var since = maxOf(nowMs - BACKFILL_WINDOW_MS, if (cursor > 0L) cursor - OVERLAP_MS else 0L)
        var inserted = 0
        var repost = 0
        var pages = 0
        while (true) {
            if (pages >= MAX_PAGES_PER_VEHICLE) return VehicleOutcome(inserted, repost, true, null)
            val page = backend.fetchObdSamplesPage(serverId, since).getOrElse {
                return VehicleOutcome(inserted, repost, false, it.message ?: "The engine could not be reached.")
            }
            val stored = storePage(app, db, mac, page.samples)
            inserted += stored.inserted
            repost += stored.repostPending
            pages++
            // Only now, with the page committed, does the cursor move.
            val newest = page.samples.maxOfOrNull { it.recordedAtMs }
            if (newest != null && newest > cursor) {
                cursor = newest
                FleetPullCursor.advance(app, key, newest)
            }
            val next = page.nextSinceMs ?: return VehicleOutcome(inserted, repost, false, null)
            if (next <= since) {
                return VehicleOutcome(
                    inserted, repost, false,
                    "The engine kept returning the same page of samples, so the download stopped there.",
                )
            }
            since = next
        }
    }

    private class Stored(val inserted: Int, val repostPending: Int)

    private suspend fun storePage(
        app: Context,
        db: CarDatabase,
        mac: String,
        samples: List<RemoteObdSample>,
    ): Stored {
        if (samples.isEmpty()) return Stored(0, 0)
        val dao = db.odbSampleDao()
        val minTs = samples.minOf { it.recordedAtMs }
        val maxTs = samples.maxOf { it.recordedAtMs }
        var maxBefore = 0L
        var maxAfter = 0L
        var count = 0
        db.withTransaction {
            maxBefore = dao.maxId() ?: 0L
            val have = samples.map { it.pid }.toSet()
                .flatMap { pid -> dao.getRange(mac, pid, minTs, maxTs).map { pid to it.timestamp } }
                .toHashSet()
            val fresh = samples.filter { have.add(it.pid to it.recordedAtMs) }.map {
                OdbSample(
                    vehicleId = mac, pid = it.pid, value = it.value, unit = it.unit,
                    timestamp = it.recordedAtMs, lat = it.lat, lng = it.lng,
                )
            }
            if (fresh.isNotEmpty()) dao.insertAll(fresh)
            count = fresh.size
            maxAfter = dao.maxId() ?: maxBefore
        }
        if (count == 0) return Stored(0, 0)

        val uploaded = ObdSampleUploadCursor.lastUploadedId(app)
        return if (uploaded >= maxBefore) {
            if (maxAfter > uploaded) ObdSampleUploadCursor.advance(app, maxAfter)
            Stored(count, 0)
        } else {
            Stored(count, count)
        }
    }
}
