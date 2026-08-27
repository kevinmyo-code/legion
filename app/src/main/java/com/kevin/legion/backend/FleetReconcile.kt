package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Drive
import com.kevin.legion.data.local.ServiceHistoryReplica
import com.kevin.legion.data.local.VehicleReplica
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import org.json.JSONObject

/**
 * The one-time (and re-runnable) Phase 4 step 1/2 job for Fleet's first wave -
 * `.scratch/backend-erp/issues/10-fleet-cutover.md`: `vehicles`, `service_history` and `drives`
 * only. Later waves add `code_events`, `oil_analyses`, `vehicle_specs`, `build_entries` and
 * `drive_reassignments` on top of this same shape - see that ticket's "the order that matters"
 * for why vehicles come first here too.
 *
 * **Two identity shapes in one reconcile, because the aspect genuinely has two.** `vehicles` and
 * `service_history` are engine records (`FleetAspectSeeder`), uploaded keyed on `origin_guid` from
 * `records.guid`, exactly like [PantryReconcile]/[EventsReconcile]. `drives` is a legacy
 * [com.kevin.legion.data.local.Drive] row with no engine counterpart at all - it upserts by
 * [Drive.syncId] instead, exactly like [PlacesReconcile]'s label-keyed upsert. Confirmed against
 * `engine/fleet/FleetAspectSeeder.kt` (only Vehicle/ServiceHistory/MaintenanceSchedule are engine
 * record types) and `supabase/migrations/20260826000200_fleet_drives.sql`'s own header comment.
 *
 * **The vehicle-id translation problem this reconcile exists to solve.** A `ServiceHistory` engine
 * record references its vehicle by that vehicle's ENGINE record id (a `Long`, local to this
 * device's `records` table). A [Drive] row references its vehicle by [com.kevin.legion.data.local.Vehicle.obdMac]
 * (a `String`, the legacy Room primary key). Neither is the identity `public.service_history.vehicle_id`/
 * `public.drives.vehicle_id` need - both server columns are foreign keys to `public.vehicles.id`, a
 * uuid that does not exist until [FleetBackend.uploadMigratedVehicle] mints one. So vehicles upload
 * FIRST, every step below builds a map from whatever local identity it started with to that minted
 * server uuid, and `ServiceHistory`/`Drive` rows that cannot be resolved through that map are
 * skipped rather than uploaded with a guessed or empty vehicle reference - an unresolvable parent is
 * a genuine "not yet migrated" state, not a value to paper over.
 *
 * **Fleet wave 2 (backend-erp fleet cutover follow-up): `vehicles`/`service_history` now DO get a
 * Room replica.** Wave 1 (above paragraph, kept for history) reasoned this out rather than building
 * it: the legacy `Vehicle` table is keyed on `obdMac`, which [FleetRecordBridge]'s own class doc says
 * is NOT recoverable from the engine's deterministic guid hash, and it carries a dozen local-only
 * columns (persona, telemetry accumulators, archive state) a server-shaped refill has no value for
 * and would either have to invent or silently blank - exactly the whole-row-clobber trap
 * `VehicleDao.upsertStamped`'s own doc comment warns about for a much smaller mismatch. The same
 * reasoning applies to `ServiceRecord` (keyed on an obdMac-derived `vehicleId` string it cannot
 * reconstruct from a server uuid either). Wave 2 builds the NEW Room tables the way
 * [com.kevin.legion.data.local.EventReplica] was built for Notes+Dates -
 * [com.kevin.legion.data.local.VehicleReplica]/[com.kevin.legion.data.local.ServiceHistoryReplica],
 * v42 - and refills both after every fetch below, so [VehicleReport]/[ServiceHistoryReport] now carry
 * a real `replicaCountAfter`, same as [DriveReport]'s. **Neither replica gets an id-preserving
 * upsert** - see [com.kevin.legion.data.local.VehicleReplica]'s own doc comment for the trace that
 * established nothing in the app addresses either table by a stable local id, unlike
 * [com.kevin.legion.data.local.EventReplica]'s alarm-request-code exposure. **Repointing a live read
 * at either replica is still later work** - this wave only keeps them filled.
 *
 * **`drives` DOES get a working replica refill, with no migration needed.** The legacy [Drive]
 * table already has exactly the columns the server table has (`vehicleId`/`startedAt`/`endedAt`/
 * `miles`/`gallons`/`endReason`) plus its own natural key (`syncId`), and it carries none of
 * `Vehicle`'s local-only baggage - see [Drive]'s own class doc: "no update, no delete", an
 * append-only fact table. So it plays the same dual role Places' `TaggedPlace`/Pantry's
 * `PantryReceipt` play: source of the upload AND the thing refilled from the server afterwards.
 * The only reason it cannot use a plain `OnConflictStrategy.REPLACE` upsert the way `TaggedPlace`
 * does is that its primary key is an autoincrement `id`, not `syncId` - [DriveDao.getBySyncId] plus
 * a plain "insert if absent" check does the same job without needing a new unique index (and
 * therefore without needing a migration either).
 *
 * **Never touches, trashes, or deletes an engine record or a [Drive] row.** Same posture as every
 * other Phase 4 reconcile - the engine (and, for drives, the legacy table itself) stays the source
 * of truth until [VehicleReport.isClean]/[ServiceHistoryReport.isClean]/[DriveReport.isClean].
 */
object FleetReconcile {

    /** @param engineCount active engine `Vehicle` records this device had.
     * @param uploaded how many were genuinely NEW server-side this run (a re-run reporting 0 is the
     *   expected idempotent outcome - see [PantryReconcile.Report.uploaded]'s own doc for why a
     *   `false` from [FleetBackend.uploadMigratedVehicle] must not inflate this count).
     * @param serverCountAfter the server's active vehicle count after the upload.
     * @param onlyOnEngine engine record guids the server has no matching `origin_guid` for.
     * @param onlyOnServer server `origin_guid`s (migrated only - a server-native vehicle carries a
     *   null `origin_guid` and is correctly excluded, same as [PantryReconcile.Report.onlyOnServer]'s
     *   own note) with no matching engine record.
     * @param replicaCountAfter the `vehicles_replica` table's own active row count after being
     *   refilled - wave 2's addition, see this object's own class doc for why wave 1 shipped without
     *   it (the gap lived in the TYPE, not just the count). */
    data class VehicleReport(
        val engineCount: Int,
        val uploaded: Int,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    /** Same shape as [VehicleReport], for `ServiceHistory`. [skippedUnresolvedVehicle] is a THIRD
     * bucket alongside "uploaded" and "already there" - it names every engine `ServiceHistory` row
     * whose parent `Vehicle` engine record has no server counterpart yet, so a caller can tell
     * "genuinely nothing to do" apart from "blocked on its own vehicle uploading first". A non-empty
     * list here on a run where [VehicleReport.isClean] is true would itself be a bug worth
     * investigating - it would mean a service-history row references a vehicle no LONGER present in
     * the current engine vehicle set at all. */
    data class ServiceHistoryReport(
        val engineCount: Int,
        val uploaded: Int,
        val skippedUnresolvedVehicle: List<String>,
        val serverCountAfter: Int,
        /** The `service_history_replica` table's own active row count after being refilled - same
         * wave-2 addition as [VehicleReport.replicaCountAfter]. */
        val replicaCountAfter: Int,
        val onlyOnEngine: List<String>,
        val onlyOnServer: List<String>,
    ) {
        val isClean: Boolean get() = onlyOnEngine.isEmpty() && onlyOnServer.isEmpty()
    }

    /** @param sourceCount [Drive] rows this device had.
     * @param uploaded every drive [FleetBackend.upsertDrive] accepted (a repost of an
     *   already-present drive still counts - unlike the migrated-record reports above, there is no
     *   "already there" branch to separate out, because upsert-by-natural-key makes a re-run just as
     *   real a write as the first one, mirroring [PlacesReconcile.Report.uploaded]'s own counting
     *   rule).
     * @param skippedUnresolvedVehicle drive [Drive.syncId]s whose [Drive.vehicleId] (obdMac) has no
     *   server-side vehicle yet.
     * @param replicaCountAfter the `drives` table's own row count after being refilled - unlike
     *   [VehicleReport]/[ServiceHistoryReport], this IS populated (see this object's own class doc
     *   for why drives can safely reuse their legacy table as their own replica). */
    data class DriveReport(
        val sourceCount: Int,
        val uploaded: Int,
        val skippedUnresolvedVehicle: List<String>,
        val serverCountAfter: Int,
        val replicaCountAfter: Int,
        val onlyOnSource: List<String>,
        val onlyOnServer: List<String>,
    ) {
        val isClean: Boolean get() = onlyOnSource.isEmpty() && onlyOnServer.isEmpty()
    }

    data class Report(
        val vehicle: VehicleReport,
        val serviceHistory: ServiceHistoryReport,
        val drive: DriveReport,
    ) {
        val isClean: Boolean get() = vehicle.isClean && serviceHistory.isClean && drive.isClean
    }

    private data class EngineVehicle(
        val engineRecordId: Long,
        val guid: String,
        val name: String,
        val make: String,
        val model: String,
        val year: Int,
        val trim: String?,
        val engine: String?,
        val confirmed: Boolean,
        val odometerBaseline: Int?,
        val odometerBaselineAtMs: Long?,
    )

    private data class EngineServiceHistory(
        val guid: String,
        val vehicleEngineRecordId: Long?,
        val serviceName: String,
        val mileage: Int?,
        val serviceDateEpochMs: Long?,
        val costCents: Long?,
        val kind: String,
    )

    suspend fun run(context: Context, backend: FleetBackend): Result<Report> {
        val db = CarDatabase.getDatabase(context)
        val sch = FleetAspectSeeder.ensureSeeded(context)

        // ---- Vehicles ---------------------------------------------------------------------------
        val engineVehicles = db.engineRecordDao().activeByRecordType(sch.vehicle.recordTypeId).mapNotNull { record ->
            val payload = JSONObject(record.payload)
            fun s(name: String) = PayloadCodec.readString(payload, sch.vehicle.fieldIds.getValue(name))
            fun l(name: String) = PayloadCodec.readLong(payload, sch.vehicle.fieldIds.getValue(name))
            val name = s(FleetAspectSeeder.FIELD_NAME) ?: return@mapNotNull null
            val make = s(FleetAspectSeeder.FIELD_MAKE) ?: return@mapNotNull null
            val model = s(FleetAspectSeeder.FIELD_MODEL) ?: return@mapNotNull null
            val year = l(FleetAspectSeeder.FIELD_YEAR)?.toInt() ?: return@mapNotNull null
            EngineVehicle(
                engineRecordId = record.id,
                guid = record.guid,
                name = name,
                make = make,
                model = model,
                year = year,
                trim = s(FleetAspectSeeder.FIELD_TRIM),
                engine = s(FleetAspectSeeder.FIELD_ENGINE),
                confirmed = PayloadCodec.readBoolean(payload, sch.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED)),
                odometerBaseline = l(FleetAspectSeeder.FIELD_ODOMETER_BASELINE)?.toInt(),
                odometerBaselineAtMs = l(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT),
            )
        }

        var vehiclesUploaded = 0
        for (v in engineVehicles) {
            val migrated = MigratedVehicle(
                originGuid = v.guid,
                name = v.name,
                make = v.make,
                model = v.model,
                year = v.year,
                trim = v.trim,
                engine = v.engine,
                confirmed = v.confirmed,
                odometerBaseline = v.odometerBaseline,
                odometerBaselineAtMs = v.odometerBaselineAtMs,
            )
            val wasNew = backend.uploadMigratedVehicle(migrated).getOrElse { return Result.failure(it) }
            if (wasNew) vehiclesUploaded++
        }

        val serverVehicles = backend.fetchActiveVehicles().getOrElse { return Result.failure(it) }
        // guid -> server uuid, the map every child upload below resolves its parent through.
        val serverIdByOriginGuid = serverVehicles.mapNotNull { row -> row.originGuid?.let { it to row.serverId } }.toMap()
        // engine record id -> guid, so a ServiceHistory row's Long vehicle reference can reach the
        // map above without a second DB round trip.
        val vehicleGuidByEngineRecordId = engineVehicles.associate { it.engineRecordId to it.guid }

        val engineVehicleGuids = engineVehicles.map { it.guid }.toSet()
        val serverVehicleOriginGuids = serverVehicles.mapNotNull { it.originGuid }.toSet()

        // Wave 2: refill the Room replica wholesale. Safe as a plain wipe-then-insert (no carried
        // id) - see VehicleReplica's own doc comment for the trace establishing nothing addresses
        // a row here by a stable local id, unlike EventReplica's alarm-request-code exposure.
        db.vehicleReplicaDao().deleteAllForReplicaRefresh()
        for (row in serverVehicles) {
            db.vehicleReplicaDao().insert(row.toReplica())
        }

        val vehicleReport = VehicleReport(
            engineCount = engineVehicles.size,
            uploaded = vehiclesUploaded,
            serverCountAfter = serverVehicles.size,
            replicaCountAfter = db.vehicleReplicaDao().getAllActive().size,
            onlyOnEngine = (engineVehicleGuids - serverVehicleOriginGuids).sorted(),
            onlyOnServer = (serverVehicleOriginGuids - engineVehicleGuids).sorted(),
        )

        // ---- ServiceHistory -----------------------------------------------------------------------
        val engineServiceHistory = db.engineRecordDao().activeByRecordType(sch.serviceHistory.recordTypeId).map { record ->
            val payload = JSONObject(record.payload)
            EngineServiceHistory(
                guid = record.guid,
                vehicleEngineRecordId = FleetRecordBridge.referenceId(record, sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE)),
                serviceName = FleetRecordBridge.serviceHistoryServiceName(record, sch.serviceHistory.fieldIds),
                mileage = FleetRecordBridge.serviceHistoryMileage(record, sch.serviceHistory.fieldIds),
                serviceDateEpochMs = FleetRecordBridge.serviceHistoryDate(record, sch.serviceHistory.fieldIds),
                costCents = PayloadCodec.readLong(payload, sch.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST)),
                kind = FleetRecordBridge.kindOf(record, sch.serviceHistory.fieldIds),
            )
        }

        var serviceHistoryUploaded = 0
        val skippedServiceHistory = mutableListOf<String>()
        val skippedServiceHistoryGuids = mutableSetOf<String>()
        for (sh in engineServiceHistory) {
            val vehicleServerId = sh.vehicleEngineRecordId
                ?.let { vehicleGuidByEngineRecordId[it] }
                ?.let { serverIdByOriginGuid[it] }
            if (vehicleServerId == null) {
                // The parent vehicle has not (yet) landed server-side - see this object's own class
                // doc for why that is a "not yet migrated" state to report, never a value to invent.
                skippedServiceHistory.add("${sh.serviceName} (${sh.guid}): vehicle not yet migrated")
                skippedServiceHistoryGuids.add(sh.guid)
                continue
            }
            val migrated = MigratedServiceHistory(
                originGuid = sh.guid,
                vehicleServerId = vehicleServerId,
                serviceName = sh.serviceName,
                mileage = sh.mileage,
                serviceDateEpochMs = sh.serviceDateEpochMs,
                costCents = sh.costCents,
                kind = sh.kind,
            )
            val wasNew = backend.uploadMigratedServiceHistory(migrated).getOrElse { return Result.failure(it) }
            if (wasNew) serviceHistoryUploaded++
        }

        val serverServiceHistory = backend.fetchActiveServiceHistory().getOrElse { return Result.failure(it) }
        // A skipped (unresolved-vehicle) row is genuinely "not yet migrated", not a diff failure -
        // it is excluded from the engine side of the comparison the same way a rejected pantry
        // receipt is excluded from PantryReconcile's engine/server guid sets, so isClean reflects
        // only what this run actually attempted to reconcile.
        val engineServiceHistoryGuids = (engineServiceHistory.map { it.guid }.toSet() - skippedServiceHistoryGuids)
        val serverServiceHistoryOriginGuids = serverServiceHistory.mapNotNull { it.originGuid }.toSet()

        // Wave 2: same wholesale refill as vehicles above - see ServiceHistoryReplica's own doc
        // comment for the same "no stable-local-id consumer" trace.
        db.serviceHistoryReplicaDao().deleteAllForReplicaRefresh()
        for (row in serverServiceHistory) {
            db.serviceHistoryReplicaDao().insert(row.toReplica())
        }

        val serviceHistoryReport = ServiceHistoryReport(
            engineCount = engineServiceHistory.size,
            uploaded = serviceHistoryUploaded,
            skippedUnresolvedVehicle = skippedServiceHistory,
            serverCountAfter = serverServiceHistory.size,
            replicaCountAfter = db.serviceHistoryReplicaDao().getAllActive().size,
            onlyOnEngine = (engineServiceHistoryGuids - serverServiceHistoryOriginGuids).sorted(),
            onlyOnServer = (serverServiceHistoryOriginGuids - engineServiceHistoryGuids).sorted(),
        )

        // ---- Drives -------------------------------------------------------------------------------
        // obdMac -> guid, computed forward from every known local vehicle (never inverted from a
        // hash - FleetRecordBridge.vehicleGuid is one-way by construction, see its own doc comment).
        val vehicles = db.vehicleDao().getAllIncludingArchived()
        val guidByObdMac = vehicles.associate { it.obdMac to FleetRecordBridge.vehicleGuid(it.obdMac) }

        val sourceDrives = db.driveDao().getAll()
        var drivesUploaded = 0
        val skippedDrives = mutableListOf<String>()
        for (drive in sourceDrives) {
            val vehicleServerId = guidByObdMac[drive.vehicleId]?.let { serverIdByOriginGuid[it] }
            if (vehicleServerId == null) {
                skippedDrives.add("${drive.syncId}: vehicle not yet migrated")
                continue
            }
            val upload = DriveUpload(
                syncId = drive.syncId,
                vehicleServerId = vehicleServerId,
                startedAtMs = drive.startedAt,
                endedAtMs = drive.endedAt,
                miles = drive.miles,
                gallons = drive.gallons,
                endReason = drive.endReason,
            )
            backend.upsertDrive(upload).getOrElse { return Result.failure(it) }
            drivesUploaded++
        }

        // Reverses serverIdByOriginGuid then guidByObdMac (server uuid -> guid -> obdMac), so a
        // server drive being pulled into the replica gets a real Drive.vehicleId (obdMac) rather
        // than the server's own uuid, which is not this column's contract - see Drive.vehicleId's
        // own doc comment. A drive whose vehicle uuid maps to no known guid/obdMac (most plausibly
        // one registered from the OTHER phone, never seen here) is skipped, never inserted with a
        // wrong or fabricated vehicle reference.
        val guidByServerId = serverIdByOriginGuid.entries.associate { (guid, serverId) -> serverId to guid }
        val obdMacByGuid = guidByObdMac.entries.associate { (obdMac, guid) -> guid to obdMac }

        val serverDrives = backend.fetchActiveDrives().getOrElse { return Result.failure(it) }
        for (row in serverDrives) {
            val vehicleObdMac = guidByServerId[row.vehicleServerId]?.let { obdMacByGuid[it] } ?: continue
            // Insert-if-absent, not a blind upsert - see DriveDao.getBySyncId's own doc comment for
            // why a finalised drive never needs its fields re-written once present.
            if (db.driveDao().getBySyncId(row.syncId) == null) {
                db.driveDao().insert(
                    Drive(
                        vehicleId = vehicleObdMac,
                        startedAt = row.startedAtMs,
                        endedAt = row.endedAtMs,
                        miles = row.miles,
                        gallons = row.gallons,
                        endReason = row.endReason,
                        syncId = row.syncId,
                    ),
                )
            }
        }

        val sourceSyncIds = sourceDrives.map { it.syncId }.toSet()
        val serverSyncIds = serverDrives.map { it.syncId }.toSet()

        val driveReport = DriveReport(
            sourceCount = sourceDrives.size,
            uploaded = drivesUploaded,
            skippedUnresolvedVehicle = skippedDrives,
            serverCountAfter = serverDrives.size,
            replicaCountAfter = db.driveDao().getAll().size,
            onlyOnSource = (sourceSyncIds - serverSyncIds).sorted(),
            onlyOnServer = (serverSyncIds - sourceSyncIds).sorted(),
        )

        return Result.success(Report(vehicleReport, serviceHistoryReport, driveReport))
    }

    /** [RemoteVehicle] -> [VehicleReplica], field for field - the Room side of the same shape.
     * No id parameter (unlike [EventsReconcile]'s [com.kevin.legion.backend.EventsReconcile.toReplica]'s):
     * every call site here wipes the table first and lets SQLite autoincrement, per
     * [VehicleReplica]'s own doc comment on why that is sufficient. */
    private fun RemoteVehicle.toReplica() = VehicleReplica(
        serverId = serverId,
        name = name,
        make = make,
        model = model,
        year = year,
        trim = trim,
        engine = engine,
        confirmed = confirmed,
        odometerBaseline = odometerBaseline,
        odometerBaselineAtMs = odometerBaselineAtMs,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
        originGuid = originGuid,
    )

    /** [RemoteServiceHistory] -> [ServiceHistoryReplica], field for field - same posture as
     * [RemoteVehicle.toReplica]. */
    private fun RemoteServiceHistory.toReplica() = ServiceHistoryReplica(
        serverId = serverId,
        vehicleServerId = vehicleServerId,
        serviceName = serviceName,
        mileage = mileage,
        serviceDateEpochMs = serviceDateEpochMs,
        costCents = costCents,
        kind = kind,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
        originGuid = originGuid,
    )
}
