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
import com.kevin.legion.data.local.Goal
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.ProactiveRaiseRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 * - **Write-through** ([goalLineageChanged], [adviceAdded], [pendingLogged], [pendingCleared],
 *   [raiseRecorded], [raiseDeclined]): best-effort and fire-and-forget after the local write
 *   committed, never failing it. A pending clear that does not reach the engine is queued in
 *   preferences and retried first on the next [run], because a deleted Room row cannot be
 *   re-derived by the reconcile.
 * - **Pull**: goals and pending transactions the ENGINE wrote (its agent tools) come down into Room
 *   by `?since=`, so the replica shows them offline. Advice and raises are written only by this
 *   phone today; they get a pull when the engine starts writing them (tickets 08 and 12).
 *
 * - **Service records** ([pushServiceRecords]): the push the Django transport used to refuse. Each
 *   reconcile sends the local records the engine has no id for, under their own `syncId`.
 *
 * Off when this device has no usable engine (no address or no token): nothing runs, nothing is
 * queued but the clears.
 */
object HomesSync {
    private const val TAG = "LegionHomes"
    private const val PREFS = "engine_homes"
    private const val KEY_DONE = "backfill_done_"
    private const val KEY_CURSOR = "pull_since_"
    private const val KEY_CLEARS = "pending_clears"
    internal const val PAGE = 200
    private const val MIN_INTERVAL_MS = 30 * 60_000L
    private const val RAISE_RECONCILE_MS = 30L * 24 * 60 * 60_000L

    /** Test seam, same mechanism as [LastAspectsWriteThrough.backendOverride]. */
    @Volatile
    internal var backendOverride: HomesBackend? = null

    @Volatile
    private var lastRunAt = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun backend(context: Context): HomesBackend? {
        backendOverride?.let { return it }
        // Never throws: a device with no engine, or a key store that cannot be read, is "off".
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
    internal suspend fun pushTable(backend: HomesBackend, table: HomeTable, room: Int, rows: List<JsonObject>): TableReport {
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
        try {
            run(context.applicationContext, backend)
        } catch (e: Exception) {
            Log.w(TAG, "homes_sync_failed ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    suspend fun run(context: Context, backend: HomesBackend, now: Long = System.currentTimeMillis()): List<TableReport> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        retryClears(context, backend)
        val reports = HomeTable.entries.map { table ->
            val first = !prefs.getBoolean(KEY_DONE + table.key, false)
            val rows = roomRows(context, table, full = first, now = now)
            val report = pushTable(backend, table, rows.size, rows)
            Log.i(TAG, report.line(first))
            if (first && report.clean) prefs.edit().putBoolean(KEY_DONE + table.key, true).apply()
            report
        }
        pullGoals(context, backend)
        pullPending(context, backend)
        fleetBackend(context)?.let { Log.i(TAG, pushServiceRecords(context, it)) }
        return reports
    }

    /** The fleet backend only when `fleet` is on the engine: the Supabase transport cannot write. */
    private fun fleetBackend(context: Context): FleetBackend? = runCatching {
        val transport = EngineTransport(context)
        if (transport.transportFor(EngineBackends.ASPECT_FLEET) != Transport.DJANGO) null else EngineBackends(context).fleetBackend()
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
        var sent = 0
        var noVehicle = 0
        var failed = 0
        for (record in unsent) {
            val vehicle = db.vehicleSidecarDao().getByMac(record.vehicleId)?.serverId
            if (vehicle == null) {
                noVehicle++
                continue
            }
            val upload = ServiceHistoryUpload(
                serverId = null,
                vehicleServerId = vehicle,
                serviceName = record.serviceName,
                mileage = record.mileage,
                serviceDateEpochMs = record.date,
                costCents = record.costCents,
                kind = record.kind,
                originGuid = record.syncId,
            )
            val remote = fleet.upsertServiceHistory(upload).getOrNull()
            if (remote == null) {
                failed++
                continue
            }
            db.serviceRecordDao().setServerId(record.id, remote.serverId)
            sent++
        }
        return "homes_backfill table=service_history room=${unsent.size} sent=$sent " +
            "skipped_no_vehicle=$noVehicle failed=$failed"
    }

    /** The live Room rows of [table] as engine rows. Raises: every one the first time, then the recent ones. */
    internal suspend fun roomRows(context: Context, table: HomeTable, full: Boolean, now: Long): List<JsonObject> {
        val db = CarDatabase.getDatabase(context)
        return when (table) {
            HomeTable.GOALS -> {
                val all = db.goalDao().getAllIncludingDeleted()
                val byId = all.associateBy { it.id }
                all.filter { !it.deleted }.map { HomesRows.goal(it, it.supersedesId?.let { id -> byId[id]?.syncId }) }
            }
            HomeTable.ADVICE -> db.advisorAdviceDao().all().map(HomesRows::advice)
            HomeTable.PENDING -> db.ledgerTransactionDao().pendingRows().mapNotNull(HomesRows::pending)
            HomeTable.RAISES -> {
                val since = if (full) 0L else now - RAISE_RECONCILE_MS
                db.proactiveRaiseDao().since(since).map(HomesRows::raise)
            }
        }
    }

    // -- write-through ----------------------------------------------------------------------------

    private fun later(context: Context, block: suspend (HomesBackend) -> Unit) {
        val backend = backend(context) ?: return
        scope.launch {
            try {
                block(backend)
            } catch (e: Exception) {
                Log.w(TAG, "homes_write_through_failed ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** After a goal was set, revised or closed: the lineage's rows, current one included. */
    fun goalLineageChanged(context: Context, lineageId: Long) = later(context) { backend ->
        val history = CarDatabase.getDatabase(context).goalDao().history(lineageId)
        val byId = history.associateBy { it.id }
        history.filter { !it.deleted }.forEach { row ->
            backend.put(HomeTable.GOALS, row.syncId, HomesRows.goal(row, row.supersedesId?.let { byId[it]?.syncId }))
        }
    }

    fun adviceAdded(context: Context, id: Long) = later(context) { backend ->
        val row = CarDatabase.getDatabase(context).advisorAdviceDao().pending(id) ?: return@later
        backend.put(HomeTable.ADVICE, row.syncId, HomesRows.advice(row))
    }

    fun pendingLogged(context: Context, row: LedgerTransaction) = later(context) { backend ->
        val json = HomesRows.pending(row) ?: return@later
        backend.put(HomeTable.PENDING, row.syncId, json)
    }

    /** Queued first, so a clear made offline is still sent; dequeued only when the engine answered. */
    fun pendingCleared(context: Context, syncId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(this) {
            val queued = prefs.getStringSet(KEY_CLEARS, emptySet()).orEmpty() + syncId
            prefs.edit().putStringSet(KEY_CLEARS, queued).apply()
        }
        later(context) { backend -> retryClears(context, backend) }
    }

    fun raiseRecorded(context: Context, row: ProactiveRaiseRow) = later(context) { backend ->
        backend.put(HomeTable.RAISES, HomesRows.raiseGuid(row.ruleId, row.spokenAt), HomesRows.raise(row))
    }

    fun raiseDeclined(context: Context, ruleId: String) = later(context) { backend ->
        val row = CarDatabase.getDatabase(context).proactiveRaiseDao().lastForRule(ruleId) ?: return@later
        backend.put(HomeTable.RAISES, HomesRows.raiseGuid(row.ruleId, row.spokenAt), HomesRows.raise(row))
    }

    private suspend fun retryClears(context: Context, backend: HomesBackend) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queued = prefs.getStringSet(KEY_CLEARS, emptySet()).orEmpty()
        // The backend answers a clear of a row the engine never held (logged and cleared offline)
        // as done, so only an unreachable engine keeps a clear queued.
        val sent = queued.filter { backend.delete(HomeTable.PENDING, it).isSuccess }.toSet()
        if (sent.isEmpty()) return
        synchronized(this) {
            val left = prefs.getStringSet(KEY_CLEARS, emptySet()).orEmpty() - sent
            prefs.edit().putStringSet(KEY_CLEARS, left).apply()
        }
    }

    internal fun queuedClears(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_CLEARS, emptySet()).orEmpty()

    // -- pull -------------------------------------------------------------------------------------

    private fun cursor(context: Context, table: HomeTable): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURSOR + table.key, null)

    private fun advance(context: Context, table: HomeTable, rows: List<JsonObject>) {
        val newest = rows.mapNotNull { HomesRows.textOf(it, "updated_at") }.maxOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURSOR + table.key, newest).apply()
    }

    internal suspend fun pullGoals(context: Context, backend: HomesBackend) {
        val remote = backend.changedSince(HomeTable.GOALS, cursor(context, HomeTable.GOALS)).getOrElse { return }
        val dao = CarDatabase.getDatabase(context).goalDao()
        val local = dao.getAllIncludingDeleted().associateBy { it.syncId }.toMutableMap()
        for (row in remote) {
            val guid = HomesRows.guidOf(row) ?: continue
            val supersedes = HomesRows.textOf(row, "supersedes_guid")?.let { local[it]?.id }
            val incoming = HomesRows.goalFromEngine(row, supersedes) ?: continue
            val existing = local[guid]
            when {
                existing == null && !incoming.deleted -> local[guid] = incoming.copy(id = dao.insert(incoming))
                existing != null && incoming.updatedAt > existing.updatedAt -> dao.update(merged(existing, incoming))
            }
        }
        advance(context, HomeTable.GOALS, remote)
    }

    private fun merged(existing: Goal, incoming: Goal): Goal = existing.copy(
        statement = incoming.statement,
        targetValue = incoming.targetValue,
        unit = incoming.unit,
        metricKey = incoming.metricKey,
        deadlineEpoch = incoming.deadlineEpoch,
        status = incoming.status,
        closedAt = incoming.closedAt,
        updatedAt = incoming.updatedAt,
        serverId = incoming.serverId,
        deleted = incoming.deleted,
    )

    internal suspend fun pullPending(context: Context, backend: HomesBackend) {
        val remote = backend.changedSince(HomeTable.PENDING, cursor(context, HomeTable.PENDING)).getOrElse { return }
        val dao = CarDatabase.getDatabase(context).ledgerTransactionDao()
        val have = dao.pendingRows().map { it.syncId }.toSet()
        val clearing = queuedClears(context)
        for (row in remote) {
            val guid = HomesRows.guidOf(row) ?: continue
            if (HomesRows.isDeleted(row)) {
                if (guid in have) dao.deletePendingBySyncId(guid)
            } else if (guid !in have && guid !in clearing) {
                HomesRows.pendingFromEngine(row)?.let { dao.insertAll(listOf(it)) }
            }
        }
        advance(context, HomeTable.PENDING, remote)
    }
}
