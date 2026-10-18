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
import java.time.Instant
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
 * Per-vehicle ARRIVAL cursor and backfill state for [ObdSamplePull], as the engine's own strings.
 * Install-scoped [android.content.SharedPreferences], beside [FleetPullCursor].
 *
 * `arrival_at` is set once, BEFORE the first backfill page is requested, and is not touched again
 * until the backfill is done: that is the handover mark. `arrival_id` is the tiebreak half of the
 * keyset (null until a full page has been walked).
 */
internal object ObdArrivalCursors {
    private const val PREFS = "obd_arrival_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arrivalAt(context: Context, serverId: String): String? = prefs(context).getString("$serverId.at", null)

    fun arrivalId(context: Context, serverId: String): String? = prefs(context).getString("$serverId.id", null)

    fun backfillDone(context: Context, serverId: String): Boolean = prefs(context).getBoolean("$serverId.done", false)

    fun beginHandover(context: Context, serverId: String, atIso: String) {
        prefs(context).edit().putString("$serverId.at", atIso).remove("$serverId.id").apply()
    }

    fun markBackfillDone(context: Context, serverId: String) {
        prefs(context).edit().putBoolean("$serverId.done", true).apply()
    }

    /** Called only after the page this cursor points past has been stored in Room. */
    fun advance(context: Context, serverId: String, atIso: String, afterId: String?) {
        prefs(context).edit().putString("$serverId.at", atIso).putString("$serverId.id", afterId).apply()
    }
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
 * **Two phases: a bounded `recorded_at` backfill, then the ARRIVAL feed.** `recorded_at` alone cannot
 * see a head unit that uploads a drive hours late (its rows land behind any `recorded_at` cursor),
 * so once the backfill reaches the end, each run asks the engine only for what it has STORED since,
 * keyed on `(created_at, id)` and persisted as the engine's own strings in [ObdArrivalCursors].
 * See [pullVehicle] for why the handover cannot miss a row.
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
     * The arrival cursor starts this far BEFORE the moment a vehicle's first backfill began. The
     * phone's clock and the engine's can differ, and the arrival feed is keyed on the ENGINE's
     * `created_at`; ten minutes of margin makes any realistic skew harmless, and the rows it
     * re-fetches are deduped. See [pullVehicle] for why this closes the backfill-to-arrival gap.
     */
    internal const val HANDOVER_MARGIN_MS = 10 * 60 * 1000L

    /**
     * Every arrival walk starts this far BEFORE the stored cursor, with no `after_id`. The engine
     * stamps `created_at` at the START of a batch's insert statement, but a batch becomes visible
     * when it COMMITS; one that started earlier can commit after a newer one has already been
     * served, leaving it just behind the cursor. Re-asking from five minutes back catches it
     * provided it commits within that time, and the dedupe absorbs the repeated rows. The stored
     * cursor stays the engine's own string; only the request's start is moved, with
     * [java.time.Instant] arithmetic that keeps the microseconds.
     */
    internal const val ARRIVAL_OVERLAP_MS = 5 * 60 * 1000L

    /** Pages per vehicle per run (500 rows each): a year of backfill resumes over several runs
     *  rather than holding the foreground for one enormous one. Hitting it is reported, not hidden. */
    internal const val MAX_PAGES_PER_VEHICLE = 100

    /** The `recorded_at` BACKFILL cursor. A new key (the previous `obd_samples_pull:` one is
     *  abandoned) so an install that held the old cursor runs the bounded backfill once more,
     *  which the dedupe makes harmless. */
    internal const val CURSOR_PREFIX = "obd_samples_backfill:"

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
        return if (backend == null) {
            notAvailable(app, "No engine is set up on this phone.")
        } else {
            refreshVia(app, backend)
        }
    }

    private suspend fun refreshVia(app: Context, backend: FleetBackend): Report {
        runCatching { ObdSampleReconcile.run(app, backend) }
        val macs = CarDatabase.getDatabase(app).vehicleSidecarDao().getAll().associate { it.serverId to it.obdMac }
        return backend.fetchActiveVehicles().fold(
            onSuccess = { vehicles -> pull(app, backend, vehicles.map { it.serverId }, macs) },
            onFailure = { notAvailable(app, it.message ?: "The engine could not be reached.") },
        )
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
    ) {
        operator fun plus(other: VehicleOutcome) = VehicleOutcome(
            inserted + other.inserted,
            repostPending + other.repostPending,
            moreToFetch || other.moreToFetch,
            failure ?: other.failure,
        )
    }

    /**
     * Two phases per vehicle.
     *
     * 1. **Backfill** (`recorded_at`, bounded to [BACKFILL_WINDOW_MS]): walks the old feed from its
     *    own cursor to the end. A backend without an arrival feed stays in this phase forever.
     * 2. **Arrival** (`created_at`, `(created_at, id)` keyset): once the backfill reaches the end,
     *    every later run asks only for what the engine has STORED since, which finds a head unit's
     *    late upload whatever its `recorded_at`.
     *
     * **The handover cannot miss a row.** The arrival cursor is fixed at (backfill start minus
     * [HANDOVER_MARGIN_MS]) BEFORE the first backfill request and does not move until the backfill
     * is done. A row stored after that mark is therefore either inside the backfill's
     * `recorded_at` walk or found by the arrival walk (or both, deduped); a row stored before the
     * mark was already on the engine when the backfill started, so the backfill sees it if it is
     * inside the window, and the window is all this phone keeps. The only assumption is that the
     * two clocks agree to within the margin.
     */
    private suspend fun pullVehicle(
        app: Context,
        db: CarDatabase,
        backend: FleetBackend,
        serverId: String,
        mac: String,
        nowMs: Long,
    ): VehicleOutcome {
        if (ObdArrivalCursors.arrivalAt(app, serverId) == null) {
            ObdArrivalCursors.beginHandover(app, serverId, Instant.ofEpochMilli(nowMs - HANDOVER_MARGIN_MS).toString())
        }
        val arrivalMode = backend.supportsArrivalFeed && ObdArrivalCursors.backfillDone(app, serverId)
        val backfill = if (arrivalMode) EMPTY_OUTCOME else backfill(app, db, backend, serverId, mac, nowMs)
        val backfillFinished = backfill.failure == null && !backfill.moreToFetch
        if (backend.supportsArrivalFeed && backfillFinished) ObdArrivalCursors.markBackfillDone(app, serverId)
        return if (backend.supportsArrivalFeed && backfillFinished) {
            backfill + arrivalWalk(app, db, backend, serverId, mac)
        } else {
            backfill
        }
    }

    private val EMPTY_OUTCOME = VehicleOutcome(0, 0, false, null)

    private suspend fun backfill(
        app: Context,
        db: CarDatabase,
        backend: FleetBackend,
        serverId: String,
        mac: String,
        nowMs: Long,
    ): VehicleOutcome {
        val key = CURSOR_PREFIX + serverId
        var cursor = FleetPullCursor.lastPulledAtMs(app, key)
        var nextSince: Long? = maxOf(nowMs - BACKFILL_WINDOW_MS, cursor)
        var inserted = 0
        var repost = 0
        var pages = 0
        var more = false
        var failure: String? = null
        while (nextSince != null && failure == null && !more) {
            val since: Long = nextSince
            if (pages >= MAX_PAGES_PER_VEHICLE) {
                more = true
            } else {
                val result = backend.fetchObdSamplesPage(serverId, since)
                val page = result.getOrNull()
                if (page == null) {
                    failure = result.exceptionOrNull()?.message ?: "The engine could not be reached."
                } else {
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
                    nextSince = page.nextSinceMs
                    if (nextSince != null && nextSince <= since) {
                        failure = STUCK_MESSAGE
                    }
                }
            }
        }
        return VehicleOutcome(inserted, repost, more, failure)
    }

    private data class Cursor(val at: String, val afterId: String?)

    private class Step(val outcome: VehicleOutcome, val hasMore: Boolean, val cursor: Cursor)

    private const val STUCK_MESSAGE =
        "The engine kept returning the same page of samples, so the download stopped there."

    private suspend fun arrivalWalk(
        app: Context,
        db: CarDatabase,
        backend: FleetBackend,
        serverId: String,
        mac: String,
    ): VehicleOutcome {
        val start = ObdArrivalCursors.arrivalAt(app, serverId) ?: return EMPTY_OUTCOME
        var cursor = Cursor(overlapStart(start), null)
        var total = EMPTY_OUTCOME
        var pages = 0
        var walking = true
        while (walking && total.failure == null && pages < MAX_PAGES_PER_VEHICLE) {
            val step = arrivalStep(app, db, backend, serverId, mac, cursor)
            total += step.outcome
            cursor = step.cursor
            walking = step.hasMore
            pages++
        }
        val capped = walking && total.failure == null
        return if (capped) total + VehicleOutcome(0, 0, true, null) else total
    }

    private fun overlapStart(storedAt: String): String =
        runCatching { Instant.parse(storedAt).minusMillis(ARRIVAL_OVERLAP_MS).toString() }.getOrDefault(storedAt)

    /** One arrival page: fetch, store, and only then persist the cursor it leads to. */
    private suspend fun arrivalStep(
        app: Context,
        db: CarDatabase,
        backend: FleetBackend,
        serverId: String,
        mac: String,
        current: Cursor,
    ): Step {
        val result = backend.fetchObdSamplesArrivalPage(serverId, current.at, current.afterId)
        val page = result.getOrNull()
        if (page == null) {
            val why = result.exceptionOrNull()?.message ?: "The engine could not be reached."
            return Step(VehicleOutcome(0, 0, false, why), false, current)
        }
        val stored = storePage(app, db, mac, page.samples)
        val next = page.cursorReceivedAfter?.let { Cursor(it, page.cursorAfterId) } ?: current
        val stuck = page.hasMore && next == current
        if (!stuck) ObdArrivalCursors.advance(app, serverId, next.at, next.afterId)
        val outcome = VehicleOutcome(stored.inserted, stored.repostPending, false, if (stuck) STUCK_MESSAGE else null)
        return Step(outcome, page.hasMore, if (stuck) current else next)
    }

    private class Stored(val inserted: Int, val repostPending: Int)

    private suspend fun storePage(
        app: Context,
        db: CarDatabase,
        mac: String,
        samples: List<RemoteObdSample>,
    ): Stored = if (samples.isEmpty()) Stored(0, 0) else storeNonEmpty(app, db, mac, samples)

    private suspend fun storeNonEmpty(
        app: Context,
        db: CarDatabase,
        mac: String,
        samples: List<RemoteObdSample>,
    ): Stored {
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
        val uploaded = ObdSampleUploadCursor.lastUploadedId(app)
        return when {
            count == 0 -> Stored(0, 0)
            uploaded >= maxBefore -> {
                if (maxAfter > uploaded) ObdSampleUploadCursor.advance(app, maxAfter)
                Stored(count, 0)
            }
            else -> Stored(count, count)
        }
    }
}
