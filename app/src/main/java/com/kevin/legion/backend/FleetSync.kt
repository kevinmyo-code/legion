package com.kevin.legion.backend

import android.content.Context
import com.kevin.legion.MidnightEvents
import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.Drive
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.OilAnalysis
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleReplica
import com.kevin.legion.data.local.VehicleSidecar
import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.data.local.upsert
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Per-table install-scoped high-water mark for [FleetSync.pull] - same shape as
 * [LedgerConfigPullCursor]/[MemoryPullCursor]/[BodyPullCursor]. `obd_samples` gets a SEPARATE
 * watermark per vehicle server id (the string key includes it) rather than one shared one, since
 * [FleetSync.pull] downloads that table's window per configured vehicle independently - see
 * [FleetSync]'s own class doc for why.
 */
internal object FleetPullCursor {
    private const val PREFS = "fleet_pull_cursor"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastPulledAtMs(context: Context, key: String): Long = prefs(context).getLong(key, 0L)

    fun advance(context: Context, key: String, atMs: Long) {
        prefs(context).edit().putLong(key, atMs).apply()
    }
}

/**
 * Fleet's live pull (live-sync map, ticket "the missing half of fleet sync", 2026-09-03) -
 * `.scratch/live-sync/map.md`'s ticket 05 ("the same treatment for the other six aspects") applied
 * to fleet specifically, following [LedgerConfigSync]/[LedgerConfigBackfill]/[LedgerConfigRealtime]'s
 * shape (the newest template, most fixes folded in, per this ticket's own brief).
 *
 * **The blocker this exists to clear, and Kevin's ruling that unblocked it (2026-09-03).** Every
 * fleet table's LOCAL key is [Vehicle.obdMac], and `obdMac` was never uploaded (ticket 26 ruling 14:
 * "it is a MAC address, and a car can change dongles"). [com.kevin.legion.data.local.VehicleSidecar]
 * is the only mac<->serverId map and it is local-only, so on a wiped phone a pull into
 * [VehicleReplica] alone left [com.kevin.legion.vehicle.FleetEngineStore.getAll] - which still
 * enumerates the LEGACY `vehicles` table - returning nothing. Kevin's ruling: `public.vehicles`
 * gains a nullable `last_obd_mac` column, sent best-effort on every vehicle upsert
 * ([RemoteVehicle.lastObdMac]/[VehicleUpload.lastObdMac]'s own doc comments carry the reversal's
 * full reasoning and its narrowness - identity NEVER keys on it, only reconstruction does). This
 * file is where that hint gets consumed: [pullVehicles] is the ONLY place that reads it, and only
 * once per vehicle, the very first time this device sees that server row.
 *
 * **Vehicles pull FIRST, always, for the identical reason [FleetReconcile]'s own class doc gives for
 * its upload direction** - every child table's `vehicleServerId` needs a local `obdMac` to key its
 * own table on, and after [pullVehicles] runs, EVERY active server vehicle is guaranteed a
 * [VehicleSidecar] row (hint-matched or synthetic), so the `serverId -> obdMac` map built right after
 * covers every reference a child pull could possibly need to resolve. A child row whose vehicle
 * somehow still fails to resolve (should not happen given the guarantee above) is skipped, never
 * given a guessed or fabricated vehicle reference - CLAUDE.md's own "never guess" posture.
 *
 * **A vehicle with no hint, or a hint already claimed by another vehicle's sidecar, still appears and
 * is still usable.** [ActiveVehicle.newVehicleId] mints the same synthetic, non-MAC id
 * [com.kevin.legion.vehicle.VehicleController.createCarProfile] already uses for a dongle-less car
 * profile - reusing that existing convention rather than inventing a second one. The hint is never
 * fabricated to fill the gap; a car with none simply gets a synthetic local id, exactly as if it had
 * been hand-created on this phone with no dongle at all.
 *
 * **Nine tables were blocked by this, not the vehicle table itself**: `service_history`, `drives`,
 * `code_events`, `code_clear_events`, `oil_analyses`, `vehicle_specs`, `build_entries`,
 * `drive_reassignments`, `maintenance_schedules` - every one keyed on an `obdMac`-derived local
 * `vehicleId` string with no way to resolve a server vehicle uuid back to one before `vehicles`
 * itself became pullable. `chassis_quirks` is NOT one of the nine - it carries no vehicle reference
 * at all (household-shared reference data, see [RemoteChassisQuirk]'s own doc comment) and was never
 * blocked, so it stays exactly as [FleetReconcile] already handles it, out of this ticket's scope.
 * `obd_samples` gets its own windowed treatment - see [pullObdSamples]'s own doc comment.
 *
 * **Six of the nine (`drives`/`code_events`/`code_clear_events`/`oil_analyses`/`build_entries`/
 * `drive_reassignments`) are append-only local facts with no `updatedAt` column at all** (each
 * entity's own class doc: "no update, no delete") - once a row exists locally its content never
 * changes, so a "merge" against an already-present row is a genuine no-op by construction: nothing
 * is written, `TableReport.updated` never counts anything for these six. The only two real actions
 * are insert-if-absent-by-`syncId` (server has it, phone doesn't) and delete-by-`syncId` (server
 * tombstoned it, matching an "already deleted, never changes" local table's own posture) - hard
 * DELETE, not a soft-delete flag, because none of the six has a `deleted` column to set (see each
 * table's own new `deleteBySyncId` DAO method doc comment).
 *
 * **`vehicles`, `service_history` and `maintenance_schedules` are the three tables with genuine
 * cross-device LWW.** All three use a strict "remote strictly newer than the last clock this device
 * applied" comparison (`>`, not `>=`) rather than [LedgerConfigMerge]'s own `>=` - a deliberate,
 * narrow deviation from the template, made so a re-fetch of the exact boundary row (the server's own
 * `updated_at gte` filter is inclusive, so the row this pull last advanced its cursor to is fetched
 * again on the very next call, same as every `fetchChangedXSince` in this codebase) produces a real,
 * verifiable no-op - a second consecutive pull performs ZERO writes, not merely writes that happen to
 * be idempotent. `vehicle_specs` needs no LWW at all: it already REPLACEs wholesale on every upload
 * ([com.kevin.legion.data.local.VehicleSpecDao.upsertStamped] carrying the server's own `updated_at`
 * forward as the local clock), so re-applying an unchanged remote row is a harmless identical
 * overwrite, matching [FleetReconcile]'s own existing behaviour for this table exactly.
 *
 * **`maintenance_schedules` matches on `(vehicleServerId -> obdMac, serviceNameMatchKey)`**, the same
 * casing-insensitive natural key [MaintenanceScheduleReconcile]'s own class doc establishes for the
 * upload direction - reused here, never re-derived, so a schedule uploaded as "Oil Change" and pulled
 * back down never creates a same-service duplicate that differs only by case.
 *
 * **Never touches [FleetEngineStore]'s write-through, [FleetReconcile]'s or
 * [ObdSampleReconcile]/[MaintenanceScheduleReconcile]'s upload logic** - this file is a pull only. It
 * reads `vehicles_replica`/the legacy tables those objects already write, and reuses
 * [FleetBackend.countObdSamples]/the `fetchActiveX` methods not at all (this file calls only the
 * NEW `fetchChangedXSince`/[FleetBackend.fetchObdSamplesSince] methods added alongside it).
 */
object FleetSync {

    /** Same five-field shape as [LedgerConfigMerge.MergeReport] - see that class's own doc for what
     * each field counts. Reused as the per-table report for every table below, append-only or LWW
     * alike, so [maybeAutoPull]'s log line and any future dashboard read one consistent shape. */
    data class TableReport(
        val inserted: Int = 0,
        val updated: Int = 0,
        val tombstoned: Int = 0,
        val skippedLocalNewer: Int = 0,
        val skippedTombstoneNoLocalMatch: Int = 0,
    ) {
        operator fun plus(other: TableReport) = TableReport(
            inserted + other.inserted,
            updated + other.updated,
            tombstoned + other.tombstoned,
            skippedLocalNewer + other.skippedLocalNewer,
            skippedTombstoneNoLocalMatch + other.skippedTombstoneNoLocalMatch,
        )
    }

    data class PullReport(
        val vehicles: TableReport,
        val serviceHistory: TableReport,
        val drives: TableReport,
        val codeEvents: TableReport,
        val codeClearEvents: TableReport,
        val oilAnalyses: TableReport,
        val vehicleSpecs: TableReport,
        val buildEntries: TableReport,
        val driveReassignments: TableReport,
        val maintenanceSchedules: TableReport,
        /** How many vehicles THIS run reconstructed a legacy row + sidecar entry for (mac-hint match
         * or synthetic id) - equal to [vehicles]' own `inserted`, named separately so a caller never
         * has to know that equivalence to read the number that matters most for this ticket. */
        val vehiclesReconstructed: Int,
        /** Rows actually downloaded and inserted this run, inside the ~30-day window - see
         * [pullObdSamples]'s own doc comment. **Never confused with the whole history**: the phone is
         * a cache for this table. */
        val obdSamplesPulled: Int,
        /** [FleetBackend.countObdSamples]'s own whole-table (unwindowed) server count, fetched once
         * per pull so a caller can see the gap between "what the phone just cached" and "what
         * actually exists" side by side - see this class's own field-level split with
         * [obdSamplesPulled]. */
        val obdSamplesServerTotal: Long,
    ) {
        /** Sums every LWW/append-only table's report - what [maybeAutoPull]'s log line reports as
         * the pull's aggregate shape, never per-table (ten numbers in one log line would bury the
         * signal). */
        val total: TableReport
            get() = vehicles + serviceHistory + drives + codeEvents + codeClearEvents + oilAnalyses +
                vehicleSpecs + buildEntries + driveReassignments + maintenanceSchedules
    }

    private const val T_VEHICLES = "vehicles"
    private const val T_SERVICE_HISTORY = "service_history"
    private const val T_DRIVES = "drives"
    private const val T_CODE_EVENTS = "code_events"
    private const val T_CODE_CLEAR_EVENTS = "code_clear_events"
    private const val T_OIL_ANALYSES = "oil_analyses"
    private const val T_VEHICLE_SPECS = "vehicle_specs"
    private const val T_BUILD_ENTRIES = "build_entries"
    private const val T_DRIVE_REASSIGNMENTS = "drive_reassignments"
    private const val T_MAINTENANCE_SCHEDULES = "maintenance_schedules"

    /** Kevin's OBD-volume ruling (2026-09-03): "recent window only... roughly the last 30 days -
     * older rows stay on the server and are fetched only when something asks." Named here, not
     * inlined, so a reader (and a future ticket widening it) sees the number without having to find
     * the one place it is used. */
    internal const val OBD_PULL_WINDOW_DAYS = 30L
    private const val OBD_PULL_WINDOW_MS = OBD_PULL_WINDOW_DAYS * 24 * 60 * 60 * 1000L

    // =============================================================================================
    // Vehicles - the reconstruction wave every other table below depends on.
    // =============================================================================================

    private suspend fun pullVehicles(context: Context, db: CarDatabase, backend: FleetBackend): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_VEHICLES)
        val remote = backend.fetchChangedVehiclesSince(sinceMs).getOrThrow()
        var inserted = 0
        var updated = 0
        var tombstoned = 0
        var skippedLocalNewer = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val sidecar = db.vehicleSidecarDao().getByServerId(r.serverId)

            if (sidecar == null) {
                // Never seen this server vehicle on this device before.
                if (r.deleted) {
                    // A tombstone with no local match - rule 3's "skip entirely", never resurrected.
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val hint = r.lastObdMac
                // The hint is only usable if it is free - unclaimed by ANY existing sidecar or
                // legacy vehicle row. A collision (two server vehicles carrying the same stale hint,
                // or a hint that happens to match a car already known locally under a different
                // identity) must never corrupt an existing mapping - see this file's own class doc
                // on why the hint is a rebuild aid, never an identity.
                val hintFree = hint != null &&
                    db.vehicleSidecarDao().getByMac(hint) == null &&
                    db.vehicleDao().getByMac(hint) == null
                val localMac = if (hintFree) hint!! else ActiveVehicle.newVehicleId()

                // Genuine create - upsert() is safe here because localMac is guaranteed absent
                // locally (either freshly synthesised, or confirmed free above).
                db.vehicleDao().upsert(
                    Vehicle(
                        obdMac = localMac,
                        name = r.name,
                        make = r.make,
                        model = r.model,
                        year = r.year,
                        personaPrompt = "",
                        odometerBaseline = r.odometerBaseline ?: 0,
                        odometerBaselineAt = r.odometerBaselineAtMs ?: 0L,
                        tripMilesSinceBaseline = 0.0,
                        onboarded = false,
                        confirmed = r.confirmed,
                        trim = r.trim ?: "",
                        engine = r.engine ?: "",
                        archived = r.archived,
                    ),
                )
                db.vehicleSidecarDao().upsert(VehicleSidecar(serverId = r.serverId, obdMac = localMac))
                // Insert-or-update, NOT a blind insert. This branch is entered on "no local
                // SIDECAR", which is not the same condition as "no local REPLICA" - a previous
                // pull that created the replica row and then failed before its sidecar landed
                // leaves exactly that split. A blind insert then trips
                // `vehicles_replica.serverId`'s unique index and aborts the whole fleet pull,
                // taking drives and code events down with it.
                //
                // Observed on the A25 2026-09-03: replica held 3 rows, sidecar 1, and every
                // subsequent pull died on
                //   SQLiteConstraintException: UNIQUE constraint failed: vehicles_replica.serverId
                // The LWW branch below already resolved this correctly; this one did not.
                val existingForNew = db.vehicleReplicaDao().getByServerId(r.serverId)
                if (existingForNew == null) {
                    db.vehicleReplicaDao().insert(r.toReplica())
                } else {
                    db.vehicleReplicaDao().update(r.toReplica().copy(id = existingForNew.id))
                }
                inserted++
                continue
            }

            // Already known locally - LWW merge. See this file's own class doc for why this
            // comparison is strict (`>`, not `>=`): a second consecutive pull must write nothing.
            val existingReplica = db.vehicleReplicaDao().getByServerId(r.serverId)
            val localClock = existingReplica?.updatedAtMs ?: 0L
            if (r.updatedAtMs <= localClock) {
                skippedLocalNewer++
                continue
            }
            val now = System.currentTimeMillis()
            db.vehicleDao().applyPulledIdentity(
                mac = sidecar.obdMac,
                year = r.year,
                make = r.make,
                model = r.model,
                trim = r.trim ?: "",
                name = r.name,
                engine = r.engine ?: "",
                confirmed = r.confirmed,
                // A server tombstone hides the car the only way the legacy mirror can (see
                // Vehicle.archived's own doc comment: "Deliberately ARCHIVE and not DELETE") -
                // `deleted` and `archived` are different server columns, but this pull's LOCAL
                // mirror has only one hiding mechanism, so a tombstone maps onto it.
                archived = r.archived || r.deleted,
                odometerBaseline = r.odometerBaseline ?: 0,
                odometerBaselineAt = r.odometerBaselineAtMs ?: 0L,
                now = now,
            )
            if (existingReplica == null) {
                db.vehicleReplicaDao().insert(r.toReplica())
            } else {
                db.vehicleReplicaDao().update(r.toReplica().copy(id = existingReplica.id))
            }
            if (r.deleted) tombstoned++ else updated++
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_VEHICLES, it) }
        return TableReport(inserted, updated, tombstoned, skippedLocalNewer, skippedTombstoneNoLocalMatch)
    }

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
        archived = archived,
    )

    // =============================================================================================
    // ServiceHistory - legacy `service_records`, matched by serverId first (the live-cutover write's
    // own identity), origin_guid second (a migrated row that predates the live cutover).
    // =============================================================================================

    private suspend fun pullServiceHistory(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_SERVICE_HISTORY)
        val remote = backend.fetchChangedServiceHistorySince(sinceMs).getOrThrow()
        var inserted = 0
        var updated = 0
        var tombstoned = 0
        var skippedLocalNewer = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.serviceRecordDao().getByServerId(r.serverId)
                ?: r.originGuid?.let { db.serviceRecordDao().getBySyncId(it) }

            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
                db.serviceRecordDao().insert(
                    ServiceRecord(
                        vehicleId = obdMac,
                        serviceName = r.serviceName,
                        mileage = r.mileage,
                        date = r.serviceDateEpochMs,
                        costCents = r.costCents,
                        syncId = r.originGuid ?: java.util.UUID.randomUUID().toString(),
                        deleted = false,
                        kind = r.kind,
                        updatedAt = r.updatedAtMs,
                        serverId = r.serverId,
                    ),
                )
                inserted++
                continue
            }

            if (r.updatedAtMs <= local.updatedAt) {
                skippedLocalNewer++
                continue
            }
            db.serviceRecordDao().applyPulledMerge(
                id = local.id,
                serviceName = r.serviceName,
                mileage = r.mileage,
                date = r.serviceDateEpochMs,
                costCents = r.costCents,
                kind = r.kind,
                deleted = r.deleted,
                updatedAt = r.updatedAtMs,
                serverId = r.serverId,
            )
            if (r.deleted) tombstoned++ else updated++
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_SERVICE_HISTORY, it) }
        return TableReport(inserted, updated, tombstoned, skippedLocalNewer, skippedTombstoneNoLocalMatch)
    }

    // =============================================================================================
    // Six append-only, syncId-keyed tables - insert-if-absent, delete-on-tombstone, nothing else (see
    // this file's own class doc for why "nothing else" is correct rather than incomplete).
    // =============================================================================================

    private suspend fun pullDrives(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_DRIVES)
        val remote = backend.fetchChangedDrivesSince(sinceMs).getOrThrow()
        var inserted = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.driveDao().getBySyncId(r.syncId)
            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
                db.driveDao().insert(
                    Drive(
                        vehicleId = obdMac,
                        startedAt = r.startedAtMs,
                        endedAt = r.endedAtMs,
                        miles = r.miles,
                        gallons = r.gallons,
                        endReason = r.endReason,
                        syncId = r.syncId,
                        serverId = r.serverId,
                    ),
                )
                inserted++
            } else if (r.deleted) {
                db.driveDao().deleteBySyncId(r.syncId)
                tombstoned++
            }
            // else: already present, remote not deleted - a Drive never changes once finalised, so
            // there is nothing to reconcile (this file's own class doc).
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_DRIVES, it) }
        return TableReport(inserted = inserted, tombstoned = tombstoned, skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch)
    }

    private suspend fun pullCodeEvents(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_CODE_EVENTS)
        val remote = backend.fetchChangedCodeEventsSince(sinceMs).getOrThrow()
        var inserted = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.codeEventDao().getBySyncId(r.syncId)
            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
                db.codeEventDao().insert(
                    CodeEvent(
                        vehicleId = obdMac,
                        timestamp = r.occurredAtMs,
                        mileage = r.mileage,
                        codesJson = r.codesJson,
                        freezeFrameJson = r.freezeFrameJson ?: "",
                        syncId = r.syncId,
                        serverId = r.serverId,
                    ),
                )
                inserted++
            } else if (r.deleted) {
                db.codeEventDao().deleteBySyncId(r.syncId)
                tombstoned++
            }
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_CODE_EVENTS, it) }
        return TableReport(inserted = inserted, tombstoned = tombstoned, skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch)
    }

    private suspend fun pullCodeClearEvents(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_CODE_CLEAR_EVENTS)
        val remote = backend.fetchChangedCodeClearEventsSince(sinceMs).getOrThrow()
        var inserted = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.codeClearEventDao().getBySyncId(r.syncId)
            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
                db.codeClearEventDao().insert(
                    CodeClearEvent(
                        vehicleId = obdMac,
                        timestamp = r.occurredAtMs,
                        mileage = r.mileage,
                        codesBeforeJson = r.codesBeforeJson,
                        freezeFrameJson = r.freezeFrameJson ?: "",
                        codesAfterJson = r.codesAfterJson ?: "",
                        outcome = r.outcome,
                        ackRaw = r.ackRaw,
                        syncId = r.syncId,
                        serverId = r.serverId,
                    ),
                )
                inserted++
            } else if (r.deleted) {
                db.codeClearEventDao().deleteBySyncId(r.syncId)
                tombstoned++
            }
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_CODE_CLEAR_EVENTS, it) }
        return TableReport(inserted = inserted, tombstoned = tombstoned, skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch)
    }

    private suspend fun pullOilAnalyses(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_OIL_ANALYSES)
        val remote = backend.fetchChangedOilAnalysesSince(sinceMs).getOrThrow()
        var inserted = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.oilAnalysisDao().getBySyncId(r.syncId)
            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
                db.oilAnalysisDao().insert(
                    OilAnalysis(
                        vehicleId = obdMac,
                        date = r.analyzedAtMs,
                        mileage = r.mileage,
                        oilBrand = r.oilBrand,
                        oilGrade = r.oilGrade,
                        drainIntervalMiles = r.drainIntervalMiles,
                        iron = r.iron,
                        copper = r.copper,
                        lead = r.lead,
                        tin = r.tin,
                        aluminum = r.aluminum,
                        chromium = r.chromium,
                        nickel = r.nickel,
                        sodium = r.sodium,
                        potassium = r.potassium,
                        silicon = r.silicon,
                        boron = r.boron,
                        magnesium = r.magnesium,
                        fuelPercent = r.fuelPercent,
                        waterPercent = r.waterPercent,
                        tbn = r.tbn,
                        viscosityCst = r.viscosityCst,
                        labNotes = r.labNotes,
                        syncId = r.syncId,
                        // OilAnalysis has no serverId column at all (unlike Drive/CodeEvent) - see
                        // that entity's own class doc.
                    ),
                )
                inserted++
            } else if (r.deleted) {
                db.oilAnalysisDao().deleteBySyncId(r.syncId)
                tombstoned++
            }
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_OIL_ANALYSES, it) }
        return TableReport(inserted = inserted, tombstoned = tombstoned, skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch)
    }

    private suspend fun pullBuildEntries(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_BUILD_ENTRIES)
        val remote = backend.fetchChangedBuildEntriesSince(sinceMs).getOrThrow()
        var inserted = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.buildEntryDao().getBySyncId(r.syncId)
            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
                db.buildEntryDao().insert(
                    BuildEntry(
                        vehicleId = obdMac,
                        type = r.entryType,
                        title = r.title,
                        vendor = r.vendor,
                        partNumber = r.partNumber,
                        // Long cents -> Double dollars, the reverse of FleetReconcile's own upload
                        // conversion (see that object's own `dollarsToCentsOrNull` doc comment).
                        cost = r.costCents?.let { it / 100.0 },
                        date = r.loggedAtMs,
                        mileage = r.mileage,
                        notes = r.notes,
                        syncId = r.syncId,
                        serverId = r.serverId,
                    ),
                )
                inserted++
            } else if (r.deleted) {
                db.buildEntryDao().deleteBySyncId(r.syncId)
                tombstoned++
            }
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_BUILD_ENTRIES, it) }
        return TableReport(inserted = inserted, tombstoned = tombstoned, skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch)
    }

    /** `drive_reassignments` is the one table in this wave with TWO vehicle references per row - see
     * [RemoteDriveReassignment]'s own doc comment. Both legs must resolve or the whole row is
     * skipped, same "never guess one leg" posture [FleetReconcile]'s own upload direction already
     * applies. **Known limitation, named rather than fixed here**: inserting a pulled reassignment
     * does not re-apply it against local `obd_samples` the way
     * [com.kevin.legion.vehicle.VehicleController.reassignDrive]'s own local apply does at write
     * time - a reassignment authored on the OTHER phone will show up here as a row on file, but this
     * device's own `obd_samples` will not be re-keyed by it until something else triggers that apply.
     * Out of this ticket's scope; the drive itself is never lost, only the correction's local effect
     * is delayed. */
    private suspend fun pullDriveReassignments(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_DRIVE_REASSIGNMENTS)
        val remote = backend.fetchChangedDriveReassignmentsSince(sinceMs).getOrThrow()
        var inserted = 0
        var tombstoned = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val local = db.driveReassignmentDao().getBySyncId(r.syncId)
            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                val fromMac = obdMacByServerId[r.vehicleServerId] ?: continue
                val toMac = obdMacByServerId[r.newVehicleServerId] ?: continue
                db.driveReassignmentDao().insert(
                    DriveReassignment(
                        syncId = r.syncId,
                        vehicleId = fromMac,
                        fromMs = r.fromAtMs,
                        toMs = r.toAtMs,
                        newVehicleId = toMac,
                        updatedAt = r.updatedAtMs,
                        serverId = r.serverId,
                    ),
                )
                inserted++
            } else if (r.deleted) {
                db.driveReassignmentDao().deleteBySyncId(r.syncId)
                tombstoned++
            }
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_DRIVE_REASSIGNMENTS, it) }
        return TableReport(inserted = inserted, tombstoned = tombstoned, skippedTombstoneNoLocalMatch = skippedTombstoneNoLocalMatch)
    }

    // =============================================================================================
    // VehicleSpec - REPLACE-per-vehicle, no tombstone column, no LWW branch needed (see this file's
    // own class doc).
    // =============================================================================================

    private suspend fun pullVehicleSpecs(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_VEHICLE_SPECS)
        val remote = backend.fetchChangedVehicleSpecsSince(sinceMs).getOrThrow()
        var updated = 0

        for (r in remote) {
            val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
            db.vehicleSpecDao().upsertStamped(
                VehicleSpec(
                    vehicleId = obdMac,
                    vin = r.vin,
                    engineCylinders = r.engineCylinders,
                    displacementL = r.displacementL,
                    engineHp = r.engineHp,
                    engineConfig = r.engineConfig,
                    fuelType = r.fuelType,
                    transmissionStyle = r.transmissionStyle,
                    transmissionSpeeds = r.transmissionSpeeds,
                    driveType = r.driveType,
                    bodyClass = r.bodyClass,
                    doors = r.doors,
                    series = r.series,
                    vehicleType = r.vehicleType,
                    manufacturer = r.manufacturer,
                    plantCity = r.plantCity,
                    plantCountry = r.plantCountry,
                    paintColor = r.paintColor,
                    paintCode = r.paintCode,
                    buildNotes = r.buildNotes,
                    decodedAt = r.decodedAtMs ?: 0L,
                    updatedAt = r.updatedAtMs,
                ),
            )
            updated++
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_VEHICLE_SPECS, it) }
        return TableReport(updated = updated)
    }

    // =============================================================================================
    // MaintenanceSchedule - the third real LWW table, matched on (obdMac, serviceNameMatchKey).
    // =============================================================================================

    private suspend fun pullMaintenanceSchedules(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): TableReport {
        val sinceMs = FleetPullCursor.lastPulledAtMs(context, T_MAINTENANCE_SCHEDULES)
        val remote = backend.fetchChangedMaintenanceSchedulesSince(sinceMs).getOrThrow()
        val localByKey = db.maintenanceItemDao().getAllIncludingDeleted()
            .associateBy { it.vehicleId to FleetRecordBridge.serviceNameMatchKey(it.serviceName) }

        var inserted = 0
        var updated = 0
        var tombstoned = 0
        var skippedLocalNewer = 0
        var skippedTombstoneNoLocalMatch = 0

        for (r in remote) {
            val obdMac = obdMacByServerId[r.vehicleServerId] ?: continue
            val matchKey = FleetRecordBridge.serviceNameMatchKey(r.serviceName)
            val local = localByKey[obdMac to matchKey]

            if (local == null) {
                if (r.deleted) {
                    skippedTombstoneNoLocalMatch++
                    continue
                }
                // insertIgnore, not insertAll/upsert - a genuine race (a concurrent local write
                // landing between the lookup above and this insert) must never clobber it. See
                // MaintenanceItemDao.insertIgnore's own doc comment.
                db.maintenanceItemDao().insertIgnore(
                    MaintenanceItem(
                        vehicleId = obdMac,
                        serviceName = r.serviceName,
                        intervalMiles = r.intervalMiles,
                        intervalMonths = r.intervalMonths,
                        updatedAt = r.updatedAtMs,
                        neverDone = r.neverDone,
                        intervalSource = r.intervalSource,
                        deleted = false,
                    ),
                )
                inserted++
                continue
            }

            if (r.updatedAtMs <= local.updatedAt) {
                skippedLocalNewer++
                continue
            }
            // Keyed by the LOCAL row's own serviceName casing - the composite primary key is exact,
            // not matchKey-normalised (same reasoning MaintenanceScheduleReconcile's own upload
            // direction states for why casing must be preserved through the identity, not the
            // content).
            db.maintenanceItemDao().applyPulledMerge(
                vehicleId = obdMac,
                serviceName = local.serviceName,
                intervalMiles = r.intervalMiles,
                intervalMonths = r.intervalMonths,
                intervalSource = r.intervalSource,
                neverDone = r.neverDone,
                deleted = r.deleted,
                updatedAt = r.updatedAtMs,
            )
            if (r.deleted) tombstoned++ else updated++
        }

        remote.maxOfOrNull { it.updatedAtMs }?.let { FleetPullCursor.advance(context, T_MAINTENANCE_SCHEDULES, it) }
        return TableReport(inserted, updated, tombstoned, skippedLocalNewer, skippedTombstoneNoLocalMatch)
    }

    // =============================================================================================
    // obd_samples - windowed, per configured vehicle, insert-if-absent (this table has no tombstone
    // column at all - see RemoteObdSample's own doc comment).
    // =============================================================================================

    /**
     * Kevin's OBD-volume ruling (2026-09-03): pull roughly the last [OBD_PULL_WINDOW_DAYS] days per
     * vehicle, never the whole history - older rows stay server-side and are fetched only when
     * something asks (no such "something" is built by this ticket; the window is the whole of this
     * table's live-sync treatment for now). The per-vehicle cursor never regresses below the current
     * window start, so the window rolls forward with real time rather than creeping backward on a
     * device that has not pulled in a while.
     *
     * **Dedup is by local lookup, not a `gt` cursor** - the server's own `recorded_at gte` filter is
     * inclusive, so the boundary sample is re-fetched on every subsequent call the same way every
     * other `fetchChangedXSince` in this file is (see this file's own class doc). [OdbSample] has no
     * unique index to lean on for an `INSERT ... ON CONFLICT DO NOTHING` locally, so this reads the
     * already-present samples for the same `(vehicleId, pid)` window once and skips anything whose
     * `(pid, timestamp)` pair is already on file, rather than risking a duplicate row per re-fetch.
     *
     * Per-vehicle failures do not abort the whole pull - one car's fetch throwing must not cost every
     * other car its telemetry for this run, matching the "per-table failures do not abort the whole
     * run" posture [LedgerConfigBackfill]'s own class doc states for a different kind of partial
     * failure.
     */
    private suspend fun pullObdSamples(
        context: Context,
        db: CarDatabase,
        backend: FleetBackend,
        obdMacByServerId: Map<String, String>,
    ): Int {
        val nowMs = System.currentTimeMillis()
        val windowStartMs = nowMs - OBD_PULL_WINDOW_MS
        var pulled = 0

        for ((serverId, obdMac) in obdMacByServerId) {
            val cursorKey = "obd_samples:$serverId"
            val sinceMs = maxOf(windowStartMs, FleetPullCursor.lastPulledAtMs(context, cursorKey))
            val remote = backend.fetchObdSamplesSince(serverId, sinceMs).getOrNull() ?: continue
            if (remote.isEmpty()) continue

            val existingByPidAndTimestamp = remote.map { it.pid }.toSet().flatMap { pid ->
                db.odbSampleDao().getRange(obdMac, pid, sinceMs, nowMs).map { pid to it.timestamp }
            }.toSet()

            for (s in remote) {
                if ((s.pid to s.recordedAtMs) in existingByPidAndTimestamp) continue
                db.odbSampleDao().insert(
                    OdbSample(
                        vehicleId = obdMac,
                        pid = s.pid,
                        value = s.value,
                        unit = s.unit,
                        timestamp = s.recordedAtMs,
                        lat = s.lat,
                        lng = s.lng,
                    ),
                )
                pulled++
            }
            remote.maxOfOrNull { it.recordedAtMs }?.let { FleetPullCursor.advance(context, cursorKey, it) }
        }

        return pulled
    }

    // =============================================================================================
    // Orchestration + foreground auto-trigger.
    // =============================================================================================

    suspend fun pull(context: Context, backend: FleetBackend): PullReport {
        val db = CarDatabase.getDatabase(context)

        val vehicles = pullVehicles(context, db, backend)
        // Built AFTER pullVehicles - every active server vehicle now has a sidecar row, hint-matched
        // or synthetic, so this map covers every vehicleServerId a child table could reference.
        val obdMacByServerId = db.vehicleSidecarDao().getAll().associate { it.serverId to it.obdMac }

        val serviceHistory = pullServiceHistory(context, db, backend, obdMacByServerId)
        val drives = pullDrives(context, db, backend, obdMacByServerId)
        val codeEvents = pullCodeEvents(context, db, backend, obdMacByServerId)
        val codeClearEvents = pullCodeClearEvents(context, db, backend, obdMacByServerId)
        val oilAnalyses = pullOilAnalyses(context, db, backend, obdMacByServerId)
        val vehicleSpecs = pullVehicleSpecs(context, db, backend, obdMacByServerId)
        val buildEntries = pullBuildEntries(context, db, backend, obdMacByServerId)
        val driveReassignments = pullDriveReassignments(context, db, backend, obdMacByServerId)
        val maintenanceSchedules = pullMaintenanceSchedules(context, db, backend, obdMacByServerId)
        val obdSamplesPulled = pullObdSamples(context, db, backend, obdMacByServerId)
        val obdSamplesServerTotal = backend.countObdSamples().getOrDefault(0L)

        return PullReport(
            vehicles = vehicles,
            serviceHistory = serviceHistory,
            drives = drives,
            codeEvents = codeEvents,
            codeClearEvents = codeClearEvents,
            oilAnalyses = oilAnalyses,
            vehicleSpecs = vehicleSpecs,
            buildEntries = buildEntries,
            driveReassignments = driveReassignments,
            maintenanceSchedules = maintenanceSchedules,
            vehiclesReconstructed = vehicles.inserted,
            obdSamplesPulled = obdSamplesPulled,
            obdSamplesServerTotal = obdSamplesServerTotal,
        )
    }

    private val autoPullScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var lastAutoPullAt = 0L

    private const val AUTO_PULL_MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val AUTO_PULL_RETRY_DELAY_MS = 1_000L

    /** Thin delegation to [SupabaseAuth.resolveSignedInUserId], same shape as
     * [LedgerConfigSync.resolveUserIdForAutoPull] - `internal` so a test can drive it directly. */
    internal suspend fun resolveUserIdForAutoPull(
        auth: SupabaseAuth,
        retryDelayMs: Long = AUTO_PULL_RETRY_DELAY_MS,
    ): String? = auth.resolveSignedInUserId(retryDelayMs)

    /** `MainActivity.onResume`'s hook. No-ops silently when Supabase is not configured or nobody is
     * signed in. Fleet has no outbox to drain first - every write reaches the server synchronously
     * through [com.kevin.legion.vehicle.FleetEngineStore]'s own tri-write or [FleetReconcile]/
     * [ObdSampleReconcile]/[MaintenanceScheduleReconcile]'s batch uploads, never queued, so there is
     * nothing this pull needs to wait on the way [EventsOutboxDrain]/[LedgerConfigOutboxDrain] make
     * their own aspects' pulls wait. */
    fun maybeAutoPull(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAutoPullAt < AUTO_PULL_MIN_INTERVAL_MS) return
        val app = context.applicationContext
        val client = SupabaseClientProvider.get(app) ?: return
        lastAutoPullAt = now
        autoPullScope.launch {
            try {
                val userId = resolveUserIdForAutoPull(SupabaseAuth(app))
                if (userId == null) return@launch
                val report = pull(app, SupabaseFleetBackend(client))
                val total = report.total
                MidnightEvents.fleetAutoPullSucceeded(
                    report.vehiclesReconstructed,
                    total.inserted,
                    total.updated,
                    total.tombstoned,
                    total.skippedLocalNewer,
                    total.skippedTombstoneNoLocalMatch,
                    report.obdSamplesPulled,
                    report.obdSamplesServerTotal,
                )
            } catch (e: Exception) {
                MidnightEvents.fleetAutoPullFailed(e)
            }
        }
    }
}
