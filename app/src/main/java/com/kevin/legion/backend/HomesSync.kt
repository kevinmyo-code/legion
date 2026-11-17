package com.kevin.legion.backend

import android.content.Context
import android.util.Log
import com.kevin.legion.backend.engine.DjangoHomesBackend
import com.kevin.legion.backend.engine.EngineBackends
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineTransport
import com.kevin.legion.backend.engine.Transport
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ServiceRecord
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/**
 * Keeps the engine homes (engine-brain ticket 03, ADR 0095) holding what this phone holds, so the
 * engine brain can read goals, advice, pending transactions and raises before the phone's own loop
 * retires. Room stays the offline replica: nothing here deletes a Room row.
 *
 * - **Backfill, once per table** ([run], from `MainActivity`'s foreground block): every live Room
 *   row goes up in pages of [PAGE] through `POST <table>/batch/`, an idempotent upsert by
 *   `origin_guid`. The table is marked done only when every page answered and no row was refused,
 *   so a run cut off halfway simply runs again next time and lands on the rows it already wrote.
 *   Each table logs one line in counts only: `homes_backfill table=goals room=N sent=N
 *   created=.. updated=.. unchanged=.. failed=.. engine=N` (the logcat spool reaches the engine's
 *   device logs, ticket 03's "backfill counts equal Room counts").
 * - **Reconcile, after that** (same call, at most every [MIN_INTERVAL_MS]): the same batch again
 *   over every row, raises only from the last [RAISE_RECONCILE_MS]. Unchanged rows cost the engine
 *   one compare. This is what repairs a write-through that failed while offline: the queue is
 *   the Room table itself.
 * - **Write-through** ([HomesWriteThrough]) and **pull** of what the engine wrote ([HomesPull]).
 * - **Service records** ([pushServiceRecords]): the push the Django transport used to refuse. Each
 *   run sends the local records the engine has no id for, under their own `syncId`.
 *
 * Off when this device has no usable engine (no address or no token): nothing runs.
 */
object HomesSync {
    internal const val TAG = "LegionHomes"
    internal const val PREFS = "engine_homes"
    private const val KEY_DONE = "backfill_done_"
    internal const val PAGE = 200
    private const val MIN_INTERVAL_MS = 30 * 60_000L
    private const val RAISE_RECONCILE_MS = 30L * 24 * 60 * 60_000L

    /** Test seam, same mechanism as [LastAspectsWriteThrough.backendOverride]. */
    @Volatile
    internal var backendOverride: HomesBackend? = null

    @Volatile
    private var lastRunAt = 0L

    /** The engine side, or null when this device has no usable engine. Never throws. */
    internal fun backend(context: Context): HomesBackend? {
        backendOverride?.let { return it }
        return runCatching {
            EngineHttp(EngineConfig(context.applicationContext)).takeIf { it.isUsable() }?.let(::DjangoHomesBackend)
        }.getOrNull()
    }

    /** One table's pass, in counts only. [complete] is false when a page failed outright. */
    data class TableReport(
        val table: HomeTable,
        val room: Int,
        val sent: Int,
        val created: Int,
        val updated: Int,
        val unchanged: Int,
        val failed: Int,
        val engine: Int,
        val complete: Boolean,
    ) {
        val clean: Boolean get() = complete && failed == 0 && sent == room

        fun line(first: Boolean): String =
            "homes_backfill table=${table.key} first=$first room=$room sent=$sent created=$created " +
                "updated=$updated unchanged=$unchanged failed=$failed engine=$engine complete=$complete"
    }

    /** Sends [rows] in pages; stops at the first page the engine did not answer. Pure over [backend]. */
    internal suspend fun pushTable(
        backend: HomesBackend,
        table: HomeTable,
        room: Int,
        rows: List<JsonObject>,
    ): TableReport {
        var report = TableReport(table, room, 0, 0, 0, 0, 0, 0, complete = true)
        for (page in rows.chunked(PAGE)) {
            val answer = backend.batch(table, page).getOrElse {
                return report.copy(complete = false)
            }
            report = report.copy(
                sent = report.sent + page.size,
                created = report.created + answer.created,
                updated = report.updated + answer.updated,
                unchanged = report.unchanged + answer.unchanged,
                failed = report.failed + answer.failed,
                engine = answer.engineCount,
            )
        }
        return report
    }

    /** `MainActivity`'s foreground hook: throttled, never throws. */
    suspend fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastRunAt < MIN_INTERVAL_MS) return
        val backend = backend(context) ?: return
        lastRunAt = now
        // A foreground hook: any failure is a log line, never a crash.
        try {
            run(context.applicationContext, backend)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "homes_sync_failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun run(
        context: Context,
        backend: HomesBackend,
        now: Long = System.currentTimeMillis(),
    ): List<TableReport> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        HomesWriteThrough.retryClears(context, backend)
        // Pull BEFORE the push: a goal the engine closed (or a pending row it cleared) reaches Room
        // first, so the batch below re-sends the newer row instead of overwriting it with Room's
        // older copy.
        HomesPull.goals(context, backend)
        HomesPull.pending(context, backend)
        val reports = HomeTable.entries.map { table ->
            val first = !prefs.getBoolean(KEY_DONE + table.key, false)
            val rows = roomRows(context, table, full = first, now = now)
            val report = pushTable(backend, table, rows.size, rows)
            Log.i(TAG, report.line(first))
            if (first && report.clean) prefs.edit().putBoolean(KEY_DONE + table.key, true).apply()
            report
        }
        fleetBackend(context)?.let { Log.i(TAG, pushServiceRecords(context, it)) }
        return reports
    }

    /** The live Room rows of [table] as engine rows. Raises: every one the first time, then the recent ones. */
    internal suspend fun roomRows(context: Context, table: HomeTable, full: Boolean, now: Long): List<JsonObject> {
        val db = CarDatabase.getDatabase(context)
        return when (table) {
            HomeTable.GOALS -> {
                val all = db.goalDao().getAllIncludingDeleted()
                val byId = all.associateBy { it.id }
                all.filter { !it.deleted }
                    .map { HomesRows.goal(it, it.supersedesId?.let { id -> byId[id]?.syncId }) }
            }
            HomeTable.ADVICE -> db.advisorAdviceDao().all().map(HomesRows::advice)
            HomeTable.PENDING -> db.ledgerTransactionDao().pendingRows().mapNotNull(HomesRows::pending)
            HomeTable.RAISES -> {
                val since = if (full) 0L else now - RAISE_RECONCILE_MS
                db.proactiveRaiseDao().since(since).map(HomesRows::raise)
            }
        }
    }

    /** The fleet backend only when `fleet` is on the engine: the Supabase transport cannot write. */
    private fun fleetBackend(context: Context): FleetBackend? = runCatching {
        val onEngine = EngineTransport(context).transportFor(EngineBackends.ASPECT_FLEET) == Transport.DJANGO
        if (onEngine) EngineBackends(context).fleetBackend() else null
    }.getOrNull()

    /**
     * The service-record push the Django transport used to refuse (ticket 03). Every local record
     * the engine has no id for goes up under its own `syncId` as `origin_guid`, an upsert, so a
     * record the 2026-09 fleet migration already uploaded under the same guid is updated, not
     * doubled. A record whose car the engine does not hold yet is skipped and counted.
     */
    internal suspend fun pushServiceRecords(context: Context, fleet: FleetBackend): String {
        val db = CarDatabase.getDatabase(context)
        val unsent = db.serviceRecordDao().getAllRecords().first().filter { !it.deleted && it.serverId == null }
        val outcomes = unsent.map { record ->
            val vehicle = db.vehicleSidecarDao().getByMac(record.vehicleId)?.serverId
            when {
                vehicle == null -> SKIPPED
                else -> fleet.upsertServiceHistory(upload(record, vehicle)).getOrNull()
                    ?.also { db.serviceRecordDao().setServerId(record.id, it.serverId) }
                    ?.let { SENT } ?: FAILED
            }
        }
        return "homes_backfill table=service_history room=${unsent.size} sent=${outcomes.count { it == SENT }} " +
            "skipped_no_vehicle=${outcomes.count { it == SKIPPED }} failed=${outcomes.count { it == FAILED }}"
    }

    private fun upload(record: ServiceRecord, vehicleServerId: String) = ServiceHistoryUpload(
        serverId = null,
        vehicleServerId = vehicleServerId,
        serviceName = record.serviceName,
        mileage = record.mileage,
        serviceDateEpochMs = record.date,
        costCents = record.costCents,
        kind = record.kind,
        originGuid = record.syncId,
    )

    private const val SENT = 0
    private const val SKIPPED = 1
    private const val FAILED = 2
}
