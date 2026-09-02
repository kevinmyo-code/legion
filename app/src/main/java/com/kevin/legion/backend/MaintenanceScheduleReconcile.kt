package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.engine.fleet.FleetRecordBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The upload path for `maintenance_schedules` - the one fleet table that had a full server schema
 * and RLS since `20260825000500_aspect_places_fleet.sql` but no writer at all: grepping
 * [FleetBackend] before this file existed turned up `vehicles`/`service_history` upserts and
 * [ObdSampleReconcile]'s own upload, and nothing else. Measured against the real phone: 52 rows on
 * the engine `MaintenanceSchedule` record type, 54 on the legacy `maintenance_items` table, 0 on
 * the server.
 *
 * **A sibling to [FleetReconcile], not a thirteenth wave inside it - same reasoning
 * [ObdSampleReconcile]'s own class doc gives, applied to a different axis.** Not volume this time
 * (52-54 rows is nothing); the reason here is that this table's SOURCE already diverged from
 * [FleetReconcile]'s own "vehicles/service_history" precedent in a way worth keeping visibly
 * separate rather than folded into that file's already-long `run()`. Engine retirement step 3
 * (ticket 16, `.scratch/backend-erp/issues/16-*`) repointed `MaintenanceSchedule`'s writes off the
 * engine and onto the legacy `maintenance_items` table (see
 * [com.kevin.legion.engine.fleet.FleetRecordBridge]'s own class doc, "ServiceHistory/
 * MaintenanceSchedule repoint off the engine and onto `service_records`/`maintenance_items`") -
 * exactly the same move [FleetReconcile]'s own `ServiceHistory` section made for the identical
 * reason, restated here rather than assumed: reading the engine's 52 rows would upload a snapshot
 * frozen at the last engine write and silently miss every schedule edit made since the repoint,
 * since there is no other route to the server for it to fall back to. This object therefore reads
 * `maintenance_items` (54 rows), never the engine.
 *
 * **No `last_done_mileage`/`last_done_date` in the upload, ever - matching both schemas' own
 * decision.** `public.maintenance_schedules`' own DDL comment says "is this due" is derived from
 * `service_history` at read time; [MaintenanceItem.lastDoneMileage]/[MaintenanceItem.lastDoneDate]
 * are the same dead, kept-but-unwritten columns [FleetRecordBridge]'s class doc already describes
 * for the local side. Inventing values for either server column would be exactly the two-stores-
 * that-can-disagree shape ticket 29 fixed, applied to Postgres instead of Room.
 *
 * **A schedule with neither interval is skipped, never uploaded** -
 * `maintenance_schedules_has_an_interval` (`interval_miles is not null or interval_months is not
 * null`) is a real CHECK constraint this table's own DDL states, not a soft preference, and a real
 * local orphan exists on file ([MaintenanceItem]'s own doc comment: a `Brake Fluid`/`Brake Pads`
 * row `VehicleController.logServiceDirect` creates with no interval at all). Reported in
 * [Report.skippedNoInterval], same "named and held back, never guessed into shape" posture
 * [FleetReconcile.VehicleReport.skippedUnexportable] already established for a different table's
 * own check constraints.
 *
 * **Casing/whitespace dedup, per the brief's own instruction, using the SAME normalisation
 * [com.kevin.legion.vehicle.FleetEngineStore.toItemsLegacy] already uses for its own local join -
 * never a second rule.** [MaintenanceScheduleUpload.serviceName] carries the server's OWN casing
 * when a server row for this `(vehicleServerId, service-name-match-key)` pair already exists,
 * looked up via [FleetRecordBridge.serviceNameMatchKey], rather than the local row's casing
 * unconditionally - `(vehicle_id, service_name)`'s uniqueness constraint compares the literal
 * string, so upserting "Oil Change" against an existing "oil change" would INSERT a second row
 * instead of updating the first. Only a fresh service (no existing server row under any casing)
 * ever uploads its own local casing.
 *
 * **REPLACE-on-conflict, not check-then-insert** - [MaintenanceScheduleUpload]'s own doc comment:
 * a schedule's interval or `neverDone` flag legitimately changes between runs (a confirmed
 * interval edit, a never-done toggle), so every call to [FleetBackend.upsertMaintenanceSchedule] is
 * expected to overwrite the server row wholesale, mirroring [FleetReconcile]'s own
 * `upsertChassisQuirk`/`upsertVehicleSpec` calls rather than its `uploadMigratedVehicle`
 * check-then-insert one. [Report.uploaded] therefore does not drop to 0 on a re-run - a repost of
 * identical data is still a real (harmless) write, same convention [DriveReport.uploaded]'s own doc
 * states.
 *
 * **Vehicle resolution reuses [FleetReconcile]'s exact mechanism, not a copy of it** - same
 * `obdMac -> guid -> server-uuid` chain [ObdSampleReconcile]'s own class doc already describes,
 * built independently here so this object can run without [FleetReconcile] having run first in the
 * SAME process (while still depending on it having run EVENTUALLY - a vehicle must exist
 * server-side before its schedules can).
 */
object MaintenanceScheduleReconcile {

    private const val PROVENANCE_USER = "USER"

    /** @param sourceCount every active `maintenance_items` row on this device, across every
     *   vehicle.
     * @param uploaded every schedule [FleetBackend.upsertMaintenanceSchedule] accepted this run - a
     *   repost still counts, per this object's own class doc on why this is a REPLACE upsert, not
     *   a check-then-insert.
     * @param skippedUnresolvedVehicle named per-row: `serviceName (obdMac)` whose vehicle has no
     *   server counterpart yet.
     * @param skippedNoInterval named per-row: a local schedule with neither `intervalMiles` nor
     *   `intervalMonths` set, which `maintenance_schedules_has_an_interval` would reject outright.
     * @param serverCountAfter the server's active maintenance-schedule count after the upload.
     * @param onlyOnSource `"vehicleServerId|serviceNameMatchKey"` keys this run attempted (i.e. not
     *   in [skippedUnresolvedVehicle]/[skippedNoInterval]) with no matching server row.
     * @param onlyOnServer the same composite keys, server-side, with no matching attempted local
     *   row - expected to be non-empty on a two-phone household where the other phone has already
     *   uploaded its own schedules for a shared vehicle. */
    data class Report(
        val sourceCount: Int,
        val uploaded: Int,
        val skippedUnresolvedVehicle: List<String>,
        val skippedNoInterval: List<String>,
        val serverCountAfter: Int,
        val onlyOnSource: List<String>,
        val onlyOnServer: List<String>,
    ) {
        val isClean: Boolean get() = onlyOnSource.isEmpty() && onlyOnServer.isEmpty()
    }

    private fun compositeKey(vehicleServerId: String, serviceName: String): String =
        "$vehicleServerId|${FleetRecordBridge.serviceNameMatchKey(serviceName)}"

    suspend fun run(context: Context, backend: FleetBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)

        // Same obdMac -> guid -> server-uuid chain FleetReconcile/ObdSampleReconcile already
        // build - independently derived here, per this object's own class doc, so it never
        // requires FleetReconcile to have run first in the same process.
        val vehicles = db.vehicleDao().getAllIncludingArchived()
        val guidByObdMac = vehicles.associate { it.obdMac to FleetRecordBridge.vehicleGuid(it.obdMac) }
        val serverVehicles = backend.fetchActiveVehicles().getOrElse { return Result.failure(it) }
        val serverIdByOriginGuid = serverVehicles.mapNotNull { row -> row.originGuid?.let { it to row.serverId } }.toMap()

        val localItems: List<MaintenanceItem> = db.maintenanceItemDao().getAllActive()

        // Existing server rows, fetched BEFORE any upload this run makes, so the casing-dedup map
        // below reflects what was already on file walking in - never a row this same run just
        // wrote (which would trivially "already match" its own local casing and defeat the point).
        val serverBefore = backend.fetchActiveMaintenanceSchedules().getOrElse { return Result.failure(it) }
        // (vehicleServerId, matchKey) -> the server's OWN casing for that service name - see this
        // object's own class doc for why the upload must reuse this string, never the local one,
        // once a match exists.
        val existingServerCasingByKey = serverBefore.associate {
            (it.vehicleServerId to FleetRecordBridge.serviceNameMatchKey(it.serviceName)) to it.serviceName
        }

        var uploaded = 0
        val skippedUnresolvedVehicle = mutableListOf<String>()
        val skippedNoInterval = mutableListOf<String>()
        val attemptedKeys = mutableSetOf<String>()

        for (item in localItems) {
            val guid = guidByObdMac[item.vehicleId]
            val vehicleServerId = guid?.let { serverIdByOriginGuid[it] }
            if (vehicleServerId == null) {
                skippedUnresolvedVehicle.add("${item.serviceName} (${item.vehicleId}): vehicle not yet migrated")
                continue
            }
            if (item.intervalMiles == null && item.intervalMonths == null) {
                // maintenance_schedules_has_an_interval would reject this outright - named and
                // held back, never guessed into shape. See this object's own class doc for the
                // real orphan (Brake Fluid/Brake Pads, no interval at all) this guards against.
                skippedNoInterval.add("${item.serviceName} (${item.vehicleId}): no interval on file")
                continue
            }

            val matchKey = FleetRecordBridge.serviceNameMatchKey(item.serviceName)
            val serviceNameToUpload = existingServerCasingByKey[vehicleServerId to matchKey] ?: item.serviceName

            backend.upsertMaintenanceSchedule(
                MaintenanceScheduleUpload(
                    vehicleServerId = vehicleServerId,
                    serviceName = serviceNameToUpload,
                    intervalMiles = item.intervalMiles,
                    intervalMonths = item.intervalMonths,
                    intervalSource = item.intervalSource,
                    neverDone = item.neverDone,
                    provenance = PROVENANCE_USER,
                ),
            ).getOrElse { return Result.failure(it) }
            uploaded++
            attemptedKeys += compositeKey(vehicleServerId, serviceNameToUpload)
        }

        val serverAfter = backend.fetchActiveMaintenanceSchedules().getOrElse { return Result.failure(it) }
        val serverKeysAfter = serverAfter.map { compositeKey(it.vehicleServerId, it.serviceName) }.toSet()

        return Result.success(
            Report(
                sourceCount = localItems.size,
                uploaded = uploaded,
                skippedUnresolvedVehicle = skippedUnresolvedVehicle,
                skippedNoInterval = skippedNoInterval,
                serverCountAfter = serverAfter.size,
                onlyOnSource = (attemptedKeys - serverKeysAfter).sorted(),
                onlyOnServer = (serverKeysAfter - attemptedKeys).sorted(),
            ),
        )
    }

    private val autoRunScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastAutoRunAt = 0L

    /** Same floor and reasoning as [LedgerReconcile]'s own `AUTO_RUN_MIN_INTERVAL_MS` - a
     * full-table scan (54 rows on the real phone), not a queue drain, so a floor stops every
     * foreground return from re-scanning the whole `maintenance_items` table. */
    private const val AUTO_RUN_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * `MainActivity.onResume`'s hook - `maintenance_schedules` had a full server schema and RLS
     * with zero writers before this ticket (see this object's own class doc). Same guard shape as
     * [EventsSync.maybeAutoPull]/[LedgerReconcile.maybeAutoRun]: no-ops silently, with a logged
     * breadcrumb, when Supabase is not configured or nobody is signed in. Fire-and-forget on
     * [autoRunScope]; never suspends the caller.
     */
    fun maybeAutoRun(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoRunAt < AUTO_RUN_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoRunAt = now
        autoRunScope.launch {
            try {
                if (SupabaseAuth(app).currentUserId() == null) return@launch
                val report = run(app, SupabaseFleetBackend(client)).getOrThrow()
                MidnightEvents.maintenanceScheduleAutoReconcileSucceeded(
                    report.uploaded,
                    report.skippedUnresolvedVehicle.size + report.skippedNoInterval.size,
                    report.serverCountAfter,
                )
            } catch (e: Exception) {
                MidnightEvents.maintenanceScheduleAutoReconcileFailed(e)
            }
        }
    }
}
