package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.kevin.legion.backend.CodeClearEventUpload
import com.kevin.legion.backend.CodeEventUpload
import com.kevin.legion.backend.BuildEntryUpload
import com.kevin.legion.backend.DriveReassignmentUpload
import com.kevin.legion.backend.DriveUpload
import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.ServiceHistoryUpload
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseFleetBackend
import com.kevin.legion.backend.VehicleSpecUpload
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.Drive
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.ServiceHistoryReplica
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleReplica
import com.kevin.legion.data.local.VehicleSidecar
import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.data.local.upsert
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.engine.migration.EngineFleetServiceHistoryRetirementCopy

/**
 * Cutover 4 (`docs/architecture/cutover4-2026-08-24.md`). The single door every fleet write and read
 * of `vehicles`/`service_records`/`maintenance_items` goes through from this branch forward -
 * [com.kevin.legion.vehicle.VehicleController] and every other consumer this cutover rewires (see
 * the cutover doc's own ruling table) call functions here instead of `db.vehicleDao()`/
 * `db.serviceRecordDao()`/`db.maintenanceItemDao()` directly, matching cutover 1/2/3's own "the
 * controller/facade keeps the callers' seam" shape (ADR 0035).
 *
 * **Why Vehicle reads are served from the LEGACY MIRROR, not from engine payload (a genuine, stated
 * fork).** [FleetAspectSeeder]'s `Vehicle` record type deliberately does not carry `obdMac` as a
 * field (`docs/architecture/wave4-carve-2026-08-23.md`'s own field-mapping table: "it is the legacy
 * NATURAL KEY... this migration's identity strategy derives the new record's guid from it instead of
 * storing it as payload data"). [FleetRecordBridge.vehicleGuid] is a ONE-WAY hash - given only an
 * engine `Vehicle` record, there is no way to recover the `obdMac` string that produced its guid, so
 * an engine-only implementation of `getAll()`/`getAllIncludingArchived()` (which must return every
 * car's own `obdMac`) is structurally impossible. The carve also left seven columns
 * (`personaPrompt`/`voiceName`/`personaTraits`/`archived`/`onboarded`/`lastOdometerPromptAt`/
 * `tripMilesSinceBaseline`) deliberately uncarried - OBD-plugin-internal or presentation state, per
 * that same table.
 *
 * The resolution: every Vehicle-identity WRITE in this file writes the engine record (authoritative -
 * it is what `ServiceHistory`/`MaintenanceSchedule`'s `REFERENCE` fields point at, and what any future
 * cross-aspect reporting sees) **and**, in the SAME `db.withTransaction` block, upserts a legacy
 * `vehicles` mirror row carrying every column (both engine-carried and legacy-only) - kept
 * byte-identical to the engine record's own nine fields by construction, since this file is the only
 * place either one is ever written. Every Vehicle READ in this file (`getByMac`/`getAll`/
 * `getAllIncludingArchived`) then serves straight from that always-in-sync mirror, which is both
 * correct (nothing but this file ever writes it) and the only place `obdMac` keys can be enumerated
 * at all. [com.kevin.legion.vehicle.TelemetryRecorder.run] keeps writing the mirror's two
 * OBD-plugin-internal columns directly (`tripMilesSinceBaseline` via `VehicleDao.addTripMiles`) -
 * per instruction 4 of the cutover brief, OBD-live state stays plugin-internal and keeps writing its
 * own table; it never touches identity or `odometerBaseline`, so it can never fight the engine write
 * this file performs for those. `markOdometerPrompted`/`clearThisCarSentinel` here are the same kind
 * of narrow, legacy-only-column writer, kept for the identical reason.
 *
 * **`ServiceHistory`/`MaintenanceSchedule` REPOINTED off the engine at engine retirement step 3
 * (`.scratch/backend-erp/issues/16-fleet-service-history-is-not-a-configured-split.md`, ticket 15's
 * "RULED... option 1", 2026-08-27).** Ticket 16 found these two record types had NO
 * configured/unconfigured split to repoint the ordinary way (they were engine-only, unconditionally,
 * by cutover 4's own design) and that the legacy `service_records` table had no `kind` column to
 * hold an `ASSERTED` row without regressing ticket 29's unification. [data.local.MIGRATION_46_47]
 * adds `kind`/`updatedAt` to `service_records` (widening `mileage`/`date` to nullable, since an
 * `ASSERTED` anchor can legitimately state only one axis) so `service_records` alone can still be
 * the ONE place a schedule check derives "last done" from - `ServiceHistory`/`MaintenanceSchedule`
 * write/read through [db.serviceRecordDao()]/[db.maintenanceItemDao()] directly now, the same
 * legacy-primary shape [getByMac]/[getAll] above already use for Vehicle.
 * [EngineFleetServiceHistoryRetirementCopy] is the one-time gap-filler that must run before this
 * branch is live on an existing install, mirroring [com.kevin.legion.engine.migration.EnginePantryRetirementCopy]'s
 * shape.
 *
 * **`MaintenanceItem.lastDoneMileage`/`.lastDoneDate` stay DERIVED, not stored, even though the
 * legacy columns exist and are nullable.** Writing the anchor VALUE into those columns again would
 * silently resurrect the exact two-independently-writable-stores bug ticket 29 fixed (see this
 * object's own [FleetRecordBridge.projectAnchorLegacy] doc for the full reasoning) - nothing in
 * this file writes them from here on; every read composes a fresh anchor from `service_records` via
 * [toItemsLegacy] instead, the identical shape [FleetRecordBridge.toMaintenanceItem]/[projectAnchor]
 * already used against the engine.
 *
 * **`backend/FleetReconcile.kt` still reads the engine directly, unchanged and out of scope for
 * this step** - it is the configured-transition upload tool, and per ticket 15's own sequencing
 * ("nothing is deleted until every aspect is repointed and soaked") the engine `ServiceHistory`/
 * `MaintenanceSchedule` records this file no longer writes stay in place, frozen at whatever this
 * install had migrated/reconciled as of the repoint. **Named consequence, not a silent one:** a
 * service logged or a schedule edited AFTER this branch lands never reaches those engine records
 * again, so a `FleetReconcile.run` on an install that keeps using the phone past this point will
 * upload an increasingly stale ServiceHistory/MaintenanceSchedule snapshot to Postgres until
 * `FleetReconcile` itself gets its own follow-up repoint - the same shape of gap
 * [MonthlyRecapController.generate]'s own comment already named for the read side pre-repoint, now
 * on the upload side post-repoint.
 *
 * **CORRECTED 2026-08-29, ticket 26 (`.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`,
 * "REVERSED 2026-08-28... fleet becomes a real cutover") - Vehicle identity ONLY, the other tables
 * are a later step.** [getByMac]/[getAll]/[getAllIncludingArchived] now compose a CONFIGURED read
 * from [VehicleReplica] (server-owned columns) + [VehicleSidecar] (phone-owned columns, ticket 14's
 * option 1) when a car has already been synced; an unconfigured install, or a car not yet synced,
 * still reads the legacy mirror exactly as before - see [composeVehicle]'s own doc comment. Every
 * identity write below (`createVehicle`/`setIdentity`/`setEngine`/`applyDecodedIdentity`/
 * `setOdometerBaseline`) still performs the engine+legacy dual write UNCHANGED, then calls
 * [syncVehicleToServer] as a best-effort THIRD write when Supabase is configured - the legacy
 * mirror stays the backbone every other fleet table's `obdMac` foreign key depends on (untouched by
 * this ticket on purpose: drives/service history/codes/specs are explicitly NOT in scope), so a
 * failed server push is logged and swallowed rather than rolled back, the same "the local write
 * already happened, do not lie about where else it landed" posture [createVehicle]'s own
 * `EngineWriteFailedException` catch block already established for the engine half.
 * `lastOdometerPromptAt`/`tripMilesSinceBaseline` writes ([markOdometerPrompted], [addTripMiles])
 * mirror into [VehicleSidecar] too, so a configured read never serves a stale phone-only value -
 * see [VehicleSidecar]'s own class doc for the real, stated consequence this carries: dropping
 * `vehicles` from [com.kevin.legion.sync.SyncEngine]'s registry in this same ticket retires the
 * Drive channel that used to carry these columns across Kevin's two phones, so they are now
 * per-device rather than per-user on a configured install.
 *
 * **CORRECTED 2026-08-29, ticket 27 (`.scratch/backend-erp/issues/27-the-sidecar-has-no-cross-device-channel.md`,
 * "RULED 2026-08-29"): `archived` is NOT one of those per-device columns after all.** It moved onto
 * `public.vehicles`/[VehicleReplica] instead - [setArchived] now pushes it through
 * [syncVehicleToServer] like any other identity field, and [composeVehicle] reads it off the
 * replica. `personaPrompt`/`voiceName`/`personaTraits` left [VehicleSidecar] in the same ticket, but
 * for the opposite reason: not because they needed a server home, but because nothing reads them at
 * all - see [Vehicle]'s own doc comment on those three fields.
 *
 * **Ticket 26 step 2 (2026-08-29): `service_history` gets the identical WRITE-side cutover** -
 * every `service_records` write below ([insertObserved], [editMileageAndCost], and the ASSERTED
 * anchor path via [writeAssertedAnchorLegacy]/[syncAssertedAnchorToServer]) now also performs a
 * best-effort third write via [syncServiceHistoryToServer], same never-throws posture as
 * [syncVehicleToServer]. **Reads are deliberately NOT repointed at the replica, unlike
 * [composeVehicle]** - every accessor below ([serviceRecordsForVehicle], [getForVehicle] via
 * [toItemsLegacy], [getServiceRecordById], etc.) still reads `service_records` unconditionally, on
 * both a configured and an unconfigured install. This is a real, considered scope line, not an
 * oversight: `Vehicle` has no numeric id at all (every reader keys on `obdMac`), so composing a
 * configured read from [VehicleReplica] cost nothing. `ServiceRecord.id` is a load-bearing local
 * surrogate - `VehicleController.editServiceRecordDirect`/`deleteServiceRecordDirect` address a row
 * by it directly, and [toItemsLegacy]'s `MaintenanceSchedule` anchor derivation groups by the same
 * rows [allHistoryForVehicle] returns - and [ServiceHistoryReplica.id] is refilled wholesale by
 * [com.kevin.legion.backend.FleetReconcile]'s own wipe-and-refill, exactly the `b17bc88` surrogate-id
 * hazard [ServiceHistoryReplica]'s own doc comment warns about, the moment ANY caller starts
 * depending on that id staying put. Building a safe read-side merge (materializing a
 * replica-only row - one written on another device - into `service_records` with a fresh local id,
 * so every existing id-keyed accessor keeps working unmodified) is real, separable follow-up work,
 * not a mechanical port of [composeVehicle]'s shape - flagged rather than built ad hoc against
 * Kevin's real service history.
 *
 * **Ticket 26 step 3 (2026-08-29): `drives` and `drive_reassignments`, together, per ticket 06's own
 * ruling that a fact and its corrections must not split across two systems.** [recordDrive] and
 * [recordDriveReassignment] are the new live write entry points - [com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive]
 * and [com.kevin.legion.vehicle.VehicleController.reassignDrive] call these now instead of
 * `db.driveDao()`/`db.driveReassignmentDao()` directly, matching this file's own "the facade keeps
 * the callers' seam" shape.
 *
 * **No new replica table for either.** [FleetBackend]'s own class doc records that `drives` and
 * five siblings already play the dual role [VehicleReplica]/[ServiceHistoryReplica] were built for -
 * the legacy `drives`/`drive_reassignments` tables are themselves what [com.kevin.legion.backend.FleetReconcile]
 * refills from the server (insert-if-absent by [Drive.syncId]/[DriveReassignment.syncId]), so a
 * configured read needs no repoint at all: it already reads the table both channels write to. This
 * is a materially SIMPLER cutover than step 2's, not an oversight - checked, not assumed, against
 * [com.kevin.legion.data.local.DriveDao]/[com.kevin.legion.data.local.DriveReassignmentDao]: neither
 * [Drive.id] nor [DriveReassignment.id] has any reader outside its own DAO (no alarm request code,
 * no soft foreign key, no Compose recomposition key even) - `service_records`' `b17bc88` hazard
 * simply does not apply here, so reads were never split from writes in the first place.
 *
 * **Identity stays [Drive.syncId]/[DriveReassignment.syncId] - ruled already, not reopened.** Ticket
 * 06 built `drives` keyed on `sync_id` specifically because it is NOT an engine record; this step
 * only adds the LIVE per-row push [com.kevin.legion.backend.FleetReconcile]'s batch job never had.
 * [Drive.serverId]/[DriveReassignment.serverId] are bookkeeping, never consulted to decide insert vs.
 * update - [com.kevin.legion.backend.DriveUpload]/[com.kevin.legion.backend.DriveReassignmentUpload]
 * upsert by the natural key server-side (`ON CONFLICT (sync_id)`), so a repost is always free by
 * construction, unlike [VehicleUpload]/[ServiceHistoryUpload]'s serverId-gated insert-vs-update
 * branch.
 *
 * **`drives`/`drive_reassignments` are dropped from `sync/SyncEngine.kt`'s `REGISTRY` in this same
 * step (ruling 05)** - Supabase is the live cross-device channel for both now. The per-drop check
 * this ticket calls for found one real thing to preserve: `SyncEngine.applyReassignments` (the
 * re-key of `obd_samples` per stored [DriveReassignment] rule) used to run gated on
 * `drive_reassignments` still being a registry entry, but that re-apply is a LOCAL SQLite operation
 * with no dependency on which channel populated the table - dropping the registry entry without
 * decoupling that call would have silently stopped reassignment rules from ever reaching
 * `obd_samples` again, on every device, regardless of Supabase. `SyncEngine.syncNow` now calls it
 * unconditionally, in the same position in the pass it always ran (immediately before `obd_samples`'
 * own turn).
 *
 * **Ticket 26 step 4 (2026-08-29): `code_events` and `code_clear_events`, the two of the
 * diagnostics trio with a real live producer.** Same "no replica, no read-side repoint" shape as
 * `drives`/`drive_reassignments`: neither table has an engine-record counterpart, and neither
 * table's local `id` has any reader outside its own DAO, so a configured read needs no change.
 * [recordCodeEvent]/[recordCodeClearEvent] are the new entry points -
 * [com.kevin.legion.service.AriaForegroundService.recordCodeEvent] and
 * [com.kevin.legion.vehicle.DtcClearController.recordOutcome] call these now instead of
 * `db.codeEventDao()`/`db.codeClearEventDao()` directly, the one caller each that creates these
 * rows. Identity is [CodeEvent.syncId]/[CodeClearEvent.syncId], ruled already at the migration that
 * introduced them (never [EngineRecord]s to begin with) - `serverId` on each is bookkeeping only,
 * never consulted to decide insert vs. update, since [CodeEventUpload]/[CodeClearEventUpload]
 * upsert by the natural key server-side (`ON CONFLICT (sync_id)`) exactly like [DriveUpload].
 * Both are dropped from `sync/SyncEngine.kt`'s `REGISTRY` in this same step (ruling 05) - the
 * per-drop check found no other code gated on either table's registry membership, unlike
 * `drive_reassignments`' `applyReassignments` call.
 *
 * **`oil_analyses`, the third of the trio, is deliberately NOT cut over in this step.** It has no
 * live write entry point anywhere in the app: [com.kevin.legion.data.local.OilAnalysisDao.insert]'s
 * only caller is [com.kevin.legion.backend.FleetReconcile]'s own batch download/reconcile path, not
 * a user-facing create - `ui/fleet/OilAnalysisDrilldown.kt`'s two `OilAnalysis(...)` calls are
 * Compose `@Preview` fixtures. There is no local write to cut over and no producer to rewire, so it
 * keeps reading/writing the legacy table exactly as before and stays in `SyncEngine`'s `REGISTRY` -
 * a table whose writes never moved keeps the only cross-device channel it has ever had. This is the
 * ticket's own "stop at a coherent boundary" clause, not an oversight.
 *
 * **Ticket 26 step 5, the last one (2026-08-29): `build_entries`, and `vehicle_specs`/
 * `chassis_quirks` are scoped as NOT-cut-over/genuinely-cut-over respectively for two different
 * reasons.** `build_entries` gets the identical "no replica, no read-side repoint" shape as
 * `drives`/`code_events` above - [recordBuildEntry] is the new entry point,
 * [com.kevin.legion.vehicle.BuildSheetController.add] calls it instead of
 * `db.buildEntryDao().insert` directly, identity is [BuildEntry.syncId] (`hasSyncId = true` in
 * `SyncEngine`'s registry, confirmed by reading it, not assumed), and [BuildEntry.serverId] is
 * bookkeeping only, mirroring [Drive.serverId] exactly.
 *
 * **`vehicle_specs` is a FOURTH identity shape, distinct from every table cut over so far.**
 * `SyncEngine` registers it with `naturalPk = true` on `vehicleId` (confirmed, not assumed), and
 * [com.kevin.legion.backend.VehicleSpecUpload]'s own doc comment confirms the server side matches:
 * `vehicle_specs.vehicle_id` IS the primary key (one row per car, REPLACE-on-conflict), not a
 * separate row id a `syncId` or `serverId` would need to track. So [upsertVehicleSpec]/
 * [syncVehicleSpecToServer] need no bookkeeping column at all - the server upsert is keyed on the
 * uuid [VehicleSidecar.serverId] already maps this car's [Vehicle.obdMac] to, and every push
 * overwrites every column, matching [com.kevin.legion.data.local.VehicleSpecDao.upsertStamped]'s
 * own local REPLACE semantics. The two producers -
 * [com.kevin.legion.vehicle.VehicleSpecController.refreshFromVin] (VIN decode) and `saveManual`
 * (driver-entered paint/notes) - both call [upsertVehicleSpec] now instead of
 * `db.vehicleSpecDao().upsert` directly.
 *
 * **`chassis_quirks` is deliberately NOT cut over in this step, for the identical reason
 * `oil_analyses` was scoped out at step 4.** Grepped every call site of
 * [com.kevin.legion.data.local.ChassisQuirkDao.upsertAll]: the only one is
 * [com.kevin.legion.backend.FleetReconcile]'s own batch download/reconcile path - there is no
 * bundled `assets/quirks.json` in this checkout at all (`ChassisQuirk`'s own class doc: "Parsed to
 * Room on first launch", but no loader exists to do that parsing), so
 * [com.kevin.legion.vehicle.CarToolbelt.quirksList]'s "No quirk index loaded yet" branch is not a
 * hypothetical - it is the only state this table has ever actually been in on a real device. There
 * is no local write to cut over and no producer to rewire, so `chassis_quirks` keeps reading/
 * writing the legacy table exactly as before and stays in `SyncEngine`'s `REGISTRY` - a table whose
 * writes never moved keeps the only cross-device channel it has ever had. A future ticket that
 * ships a real quirk-index asset and a loader should retire this registry entry in the same change,
 * matching [MIGRATION_53_54]'s own `oil_analyses` precedent.
 *
 * **This is the LAST step of ticket 26.** Every fleet table that ever had a live local producer now
 * writes through this facade and pushes to Supabase best-effort. What remains, named rather than
 * silently dropped: ticket 28 (`service_records` reads still serve the legacy table unconditionally,
 * by design - see step 2's own section above) and the `obd_samples`/`conversation_audit` upload
 * paths, whose migration `20260829000100` exists on the server side but is UNAPPLIED.
 */
object FleetEngineStore {

    /** Thrown only to force [androidx.room.withTransaction] to roll back a whole multi-step fleet
     * write - same primitive `ledger/IngestPipeline.kt`/`pantry/PantryController.kt` already use. */
    private class EngineWriteFailedException(val reason: String) : Exception()

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    private suspend fun engineVehicleId(db: CarDatabase, mac: String): Long? =
        db.engineRecordDao().getByGuid(FleetRecordBridge.vehicleGuid(mac))?.id

    /** Test seam: settable so a [FleetBackend] fake can be injected without touching
     * [SupabaseClientProvider] - same shape as
     * [com.kevin.legion.location.PlaceController.backendOverride]. */
    internal var backendOverride: FleetBackend? = null

    /** Resolves the live [FleetBackend], or null when Supabase is not configured - the caller
     * words the null case itself (here: "stay on the legacy mirror"), matching
     * [com.kevin.legion.location.PlaceController]'s own `backend(Context)` contract. */
    private fun backend(context: Context): FleetBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseFleetBackend(client)
    }

    // =============================================================================================
    // Vehicle
    // =============================================================================================

    /** Composes a configured-path [Vehicle] view from the server-owned [VehicleReplica] and the
     * phone-owned [VehicleSidecar] - ticket 14's option 1, keyed on [VehicleSidecar.serverId].
     * [Vehicle.obdMac] comes from the sidecar (the only one of the two that carries it - see
     * [VehicleSidecar]'s own class doc for why). Every field is real (not `""`/`0` guessed) even
     * though [VehicleReplica.trim]/[VehicleReplica.engine] are nullable server-side - `""` is
     * exactly what a blank driver-entered value already meant on the legacy row, so this is not a
     * new sentinel, only the existing one applied at a new boundary. */
    /** [personaPrompt]/[voiceName]/[personaTraits] are never sourced from the sidecar or the
     * replica - ticket 26/27 ruled them vestigial (nothing reads them, see [Vehicle]'s own doc
     * comment on those three fields), so a configured read simply supplies the same blank default
     * a fresh unconfigured row would have, rather than carrying dead data through a new store. */
    private fun composeVehicle(replica: VehicleReplica, sidecar: VehicleSidecar): Vehicle = Vehicle(
        obdMac = sidecar.obdMac,
        name = replica.name,
        make = replica.make,
        model = replica.model,
        year = replica.year,
        personaPrompt = "",
        odometerBaseline = replica.odometerBaseline ?: 0,
        odometerBaselineAt = replica.odometerBaselineAtMs ?: 0L,
        tripMilesSinceBaseline = sidecar.tripMilesSinceBaseline,
        lastOdometerPromptAt = sidecar.lastOdometerPromptAt,
        onboarded = sidecar.onboarded,
        voiceName = "",
        personaTraits = "",
        trim = replica.trim ?: "",
        confirmed = replica.confirmed,
        updatedAt = replica.updatedAtMs,
        archived = replica.archived,
        engine = replica.engine ?: "",
    )

    /** Configured-path compose for one [mac] - null when unconfigured, when this car has never
     * synced (no [VehicleSidecar] row yet), or when the server has tombstoned it (soft-deleted
     * remotely, [VehicleReplica.deleted]). Every one of those falls back to the legacy mirror in
     * the caller, never to a silently empty result. */
    private suspend fun composedVehicle(context: Context, db: CarDatabase, mac: String): Vehicle? {
        if (backend(context) == null) return null
        val sidecar = db.vehicleSidecarDao().getByMac(mac) ?: return null
        val replica = db.vehicleReplicaDao().getByServerId(sidecar.serverId) ?: return null
        if (replica.deleted) return null
        return composeVehicle(replica, sidecar)
    }

    suspend fun getByMac(context: Context, mac: String): Vehicle? {
        val db = CarDatabase.getDatabase(context)
        return composedVehicle(context, db, mac) ?: db.vehicleDao().getByMac(mac)
    }

    /** Active (non-archived) cars only - see [com.kevin.legion.data.local.VehicleDao.getAll]'s own doc.
     * The legacy mirror still decides WHICH macs are active (its own `archived` column is kept in
     * sync with [VehicleSidecar.archived] by every writer below), each one then composed with the
     * server view when one exists. */
    suspend fun getAll(context: Context): List<Vehicle> {
        val db = CarDatabase.getDatabase(context)
        return db.vehicleDao().getAll().map { legacy ->
            composedVehicle(context, db, legacy.obdMac) ?: legacy
        }
    }

    suspend fun getAllIncludingArchived(context: Context): List<Vehicle> {
        val db = CarDatabase.getDatabase(context)
        return db.vehicleDao().getAllIncludingArchived().map { legacy ->
            composedVehicle(context, db, legacy.obdMac) ?: legacy
        }
    }

    /**
     * Best-effort THIRD write, after the engine+legacy dual write every caller below already
     * performs unchanged. Reads the just-written legacy [Vehicle] row fresh (so it always uploads
     * the caller's actual post-write state, never a stale in-memory copy) and pushes it to
     * Supabase via [FleetBackend.upsertVehicle] - insert (mints a `serverId`) the first time a car
     * syncs, update by that `serverId` every time after ([VehicleSidecar.getByMac] is the local
     * obdMac -> serverId map, ticket 26's own "read by serverId first" rule applied here: an
     * existing mapping is always looked up before deciding insert vs. update, so a re-sync can
     * never mint a second server row for the same car).
     *
     * **Never throws, never rolls back the local write.** A network failure here means the driver's
     * action already landed locally (the engine + legacy mirror writes already committed) - failing
     * this function loudly would be reporting a LOCAL success as a total failure, the mirror image
     * of the false-success problem CLAUDE.md section 7 forbids. Logged instead, same posture
     * [createVehicle]'s own `EngineWriteFailedException` catch block already uses for the engine
     * half of this same tri-write.
     *
     * [odometerBaseline]/[odometerBaselineAtMs] are sent null-paired when the driver has never
     * actually stated a reading (`odometerBaselineAt == 0L`, the legacy "never set" sentinel) -
     * never a fabricated `0`, matching [VehicleUpload]'s own doc comment and CLAUDE.md section 4
     * rule 5.
     */
    private suspend fun syncVehicleToServer(context: Context, mac: String) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val vehicle = db.vehicleDao().getByMac(mac) ?: return
        val existingSidecar = db.vehicleSidecarDao().getByMac(mac)
        val odometerAtMs = vehicle.odometerBaselineAt.takeIf { it != 0L }

        val remote = backend.upsertVehicle(
            VehicleUpload(
                serverId = existingSidecar?.serverId,
                name = vehicle.name,
                make = vehicle.make,
                model = vehicle.model,
                year = vehicle.year,
                trim = vehicle.trim.ifBlank { null },
                engine = vehicle.engine.ifBlank { null },
                confirmed = vehicle.confirmed,
                odometerBaseline = odometerAtMs?.let { vehicle.odometerBaseline },
                odometerBaselineAtMs = odometerAtMs,
                archived = vehicle.archived,
                // Best-effort rebuild hint (ticket "fleet pull", 2026-09-03 ruling reversing part
                // of ticket 26 ruling 14 - see VehicleUpload.lastObdMac's own doc comment). Sent on
                // every sync so it always reflects the car's CURRENT dongle, never the one that
                // happened to be plugged in the first time this car synced.
                lastObdMac = mac,
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase vehicle sync failed for $mac: ${it.message}")
            return
        }

        db.vehicleReplicaDao().upsert(
            VehicleReplica(
                serverId = remote.serverId,
                name = remote.name,
                make = remote.make,
                model = remote.model,
                year = remote.year,
                trim = remote.trim,
                engine = remote.engine,
                confirmed = remote.confirmed,
                odometerBaseline = remote.odometerBaseline,
                odometerBaselineAtMs = remote.odometerBaselineAtMs,
                updatedAtMs = remote.updatedAtMs,
                deleted = remote.deleted,
                originGuid = remote.originGuid,
                archived = remote.archived,
            ),
        )
        db.vehicleSidecarDao().upsert(
            VehicleSidecar(
                serverId = remote.serverId,
                obdMac = mac,
                onboarded = vehicle.onboarded,
                lastOdometerPromptAt = vehicle.lastOdometerPromptAt,
                tripMilesSinceBaseline = vehicle.tripMilesSinceBaseline,
            ),
        )
    }

    /**
     * Genuine create only (a new dongle MAC or synthetic car-profile id with no row on file yet) -
     * writes the engine `Vehicle` record AND the legacy mirror row in one transaction. Mirrors
     * [com.kevin.legion.data.local.VehicleDao.upsert]'s own "create only, never edit an existing row
     * this way" contract - [vehicle] is expected to already be fully specified (every caller -
     * `registerDirect`'s new-row branch, `addVehicle`, `createCarProfile` - builds a complete
     * [Vehicle] before calling this, same as before cutover).
     */
    suspend fun createVehicle(context: Context, vehicle: Vehicle) {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        try {
            db.withTransaction {
                val result = recordStore.create(
                    recordTypeId = schema.vehicle.recordTypeId,
                    fieldValues = FleetRecordBridge.vehicleFieldValues(vehicle, schema.vehicle.fieldIds),
                    provenance = RecordProvenance.USER,
                    guid = FleetRecordBridge.vehicleGuid(vehicle.obdMac),
                )
                if (result is RecordStore.WriteResult.Failure) throw EngineWriteFailedException(result.reason)
                db.vehicleDao().upsert(vehicle)
            }
        } catch (e: EngineWriteFailedException) {
            // A create failing here means a duplicate obdMac raced this call (the migration/catch-up
            // copier is the only other writer of this exact guid) - fall back to the mirror-only
            // write so a driver-visible action never silently vanishes; the next engine catch-up
            // pass reconciles it. Logged, not thrown further - CLAUDE.md §7's "nothing may claim
            // success it did not observe" is about outcome VERBS spoken to the driver, which none of
            // registerDirect/addVehicle/createCarProfile's own confirmations assert beyond "I filed
            // this" - they do not promise WHERE it landed.
            android.util.Log.w("FleetEngineStore", "createVehicle engine write failed for ${vehicle.obdMac}: ${e.reason}")
            db.vehicleDao().upsert(vehicle)
        }
        syncVehicleToServer(context, vehicle.obdMac)
    }

    suspend fun setIdentity(context: Context, mac: String, year: Int, make: String, model: String, trim: String, name: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val engineId = engineVehicleId(db, mac) ?: return 0
        db.withTransaction {
            val result = recordStore.update(
                engineId,
                mapOf(
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_NAME) to name,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR) to year,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE) to make,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL) to model,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM) to trim,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED) to true,
                ),
                now,
            )
            if (result is RecordStore.WriteResult.Failure) throw EngineWriteFailedException(result.reason)
            db.vehicleDao().setIdentity(mac, year, make, model, trim, name, now)
        }
        syncVehicleToServer(context, mac)
        return 1
    }

    suspend fun setEngine(context: Context, mac: String, engine: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val engineId = engineVehicleId(db, mac) ?: return 0
        db.withTransaction {
            val result = recordStore.update(engineId, mapOf(schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ENGINE) to engine), now)
            if (result is RecordStore.WriteResult.Failure) throw EngineWriteFailedException(result.reason)
            db.vehicleDao().setEngine(mac, engine, now)
        }
        syncVehicleToServer(context, mac)
        return 1
    }

    suspend fun applyDecodedIdentity(context: Context, mac: String, year: Int, make: String, model: String, trim: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val engineId = engineVehicleId(db, mac) ?: return 0
        db.withTransaction {
            val result = recordStore.update(
                engineId,
                mapOf(
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR) to year,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE) to make,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL) to model,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM) to trim,
                    // Deliberately NOT FIELD_CONFIRMED - VehicleDao.applyDecodedIdentity's own doc:
                    // a vPIC lookup filling in blanks must never silently mark the car
                    // driver-confirmed on the driver's behalf.
                ),
                now,
            )
            if (result is RecordStore.WriteResult.Failure) throw EngineWriteFailedException(result.reason)
            db.vehicleDao().applyDecodedIdentity(mac, year, make, model, trim, now)
        }
        syncVehicleToServer(context, mac)
        return 1
    }

    /**
     * `archived` is a legacy-only field (wave4-carve's own ruling) - the legacy mirror stays the
     * sole SERVER-side-of-the-boundary store for it, same as `tripMilesSinceBaseline`/
     * `lastOdometerPromptAt`. No engine write: nothing on the engine Vehicle record type represents
     * "hidden from the roster", and inventing a field for it now would re-litigate a carve decision
     * this cutover is bound by, not empowered to reopen (wave4-carve: "revisited only if a
     * follow-up wave's cutover finds a live need").
     *
     * **CORRECTED 2026-08-29, ticket 27 (`.scratch/backend-erp/issues/27-the-sidecar-has-no-cross-device-channel.md`,
     * "RULED 2026-08-29"): `archived` moved OFF the sidecar and onto the server.** It is USER state
     * ("a car Kevin retired is retired everywhere"), not device state, so this now pushes through
     * [syncVehicleToServer] - the same best-effort third write every identity setter already
     * performs - rather than mirroring into [com.kevin.legion.data.local.VehicleSidecar]. A
     * configured read (see [composeVehicle]) sources `archived` from [VehicleReplica] now, so a
     * failed push here means the NEXT successful sync (of anything) still corrects it, same
     * "logged, not thrown, local write already happened" posture [syncVehicleToServer]'s own doc
     * comment establishes.
     */
    suspend fun setArchived(context: Context, mac: String, archived: Boolean, now: Long) {
        val db = CarDatabase.getDatabase(context)
        db.vehicleDao().setArchived(mac, archived, now)
        syncVehicleToServer(context, mac)
    }

    suspend fun setOdometerBaseline(context: Context, mac: String, miles: Int, at: Long, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val engineId = engineVehicleId(db, mac) ?: return 0
        db.withTransaction {
            val result = recordStore.update(
                engineId,
                mapOf(
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE) to miles,
                    schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT) to at,
                ),
                now,
            )
            if (result is RecordStore.WriteResult.Failure) throw EngineWriteFailedException(result.reason)
            db.vehicleDao().setOdometerBaseline(mac, miles, at, now)
        }
        // A fresh baseline also resets the sidecar's own trip accumulator, mirroring
        // VehicleDao.setOdometerBaseline's own "tripMilesSinceBaseline = 0.0" - see addTripMiles's
        // own doc comment for why that column needs a sidecar mirror at all.
        db.vehicleSidecarDao().getByMac(mac)?.let { db.vehicleSidecarDao().resetTripMiles(it.serverId) }
        syncVehicleToServer(context, mac)
        return 1
    }

    /** Legacy-only column (nag cadence) - see this file's own class doc. Mirrors into
     * [com.kevin.legion.data.local.VehicleSidecar] when synced, same reasoning as [setArchived]'s
     * own doc comment. */
    suspend fun markOdometerPrompted(context: Context, mac: String, at: Long, now: Long) {
        val db = CarDatabase.getDatabase(context)
        db.vehicleDao().markOdometerPrompted(mac, at, now)
        db.vehicleSidecarDao().getByMac(mac)?.let { db.vehicleSidecarDao().markOdometerPrompted(it.serverId, at) }
    }

    /**
     * Accumulates [Vehicle.tripMilesSinceBaseline] in both the legacy mirror (unchanged,
     * [com.kevin.legion.data.local.VehicleDao.addTripMiles]'s own SQL-side accumulation, still the
     * value every OBD-plugin-internal reader outside this file consults) and, when synced, the
     * [com.kevin.legion.data.local.VehicleSidecar] mirror - the same "don't let a configured read
     * serve a stale phone-only value" reasoning as [setArchived]. Routes
     * [com.kevin.legion.vehicle.TelemetryRecorder]'s own 30-second tick through this ONE function
     * rather than a direct `db.vehicleDao()` call, so the sidecar mirror cannot silently drift
     * behind the legacy row the way it would if a second, unaudited writer kept calling the DAO
     * directly. */
    suspend fun addTripMiles(context: Context, mac: String, delta: Double, now: Long) {
        val db = CarDatabase.getDatabase(context)
        db.vehicleDao().addTripMiles(mac, delta, now)
        db.vehicleSidecarDao().getByMac(mac)?.let { db.vehicleSidecarDao().addTripMiles(it.serverId, delta) }
    }

    /** Legacy-only sentinel cleanup - see [com.kevin.legion.data.local.VehicleDao.clearThisCarSentinel]'s own doc. */
    suspend fun clearThisCarSentinel(context: Context, now: Long) {
        CarDatabase.getDatabase(context).vehicleDao().clearThisCarSentinel(now)
    }

    /**
     * Trashes the engine `Vehicle` record. **Blockers are now checked against the LEGACY
     * `service_records`/`maintenance_items` tables, not the engine** (engine retirement step 3):
     * since [insertObserved]/[upsertNewItem]/etc. below no longer write `ServiceHistory`/
     * `MaintenanceSchedule` engine records, the engine's own `DeletePolicy.BLOCK` scan (which only
     * ever sees engine rows) would find nothing for a car whose ENTIRE history was logged after this
     * branch landed and incorrectly allow the delete - checking the tables that are now the real
     * store closes that hole. **No live caller reaches this today** - archive/unarchive
     * ([setArchived]) is the only removal affordance the app currently exposes for a car; a real
     * hard-delete voice tool or screen action does not exist, so this exists to make the refusal
     * mechanically real and testable rather than to back a live capability this branch did not add.
     * When a caller does reach it, the contract is unchanged: [RecordStore.DeleteResult.Blocked]
     * means NOTHING was written, and the caller must surface `blockers` in words - "that car has
     * service history on file, so I can't delete it" - never a silent no-op and never a partial
     * delete.
     */
    suspend fun deleteVehicle(context: Context, mac: String): RecordStore.DeleteResult {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.vehicleDao().getByMac(mac) == null) return RecordStore.DeleteResult.NotFound

        val blockers = mutableListOf<String>()
        val serviceHistoryCount = db.serviceRecordDao().countForVehicle(mac)
        if (serviceHistoryCount > 0) blockers += "$serviceHistoryCount service history record(s) still reference this car"
        val scheduleCount = db.maintenanceItemDao().getForVehicle(mac).size
        if (scheduleCount > 0) blockers += "$scheduleCount maintenance schedule item(s) still reference this car"
        if (blockers.isNotEmpty()) return RecordStore.DeleteResult.Blocked(blockers)

        val recordStore = store(db)
        val engineId = engineVehicleId(db, mac) ?: return RecordStore.DeleteResult.NotFound
        return recordStore.delete(engineId)
    }

    // =============================================================================================
    // ServiceHistory (ServiceRecord entity, OBSERVED rows only surface through the functions below
    // that return one - ASSERTED rows never do; they have no real event behind them). Repointed off
    // the engine at engine retirement step 3 - see this file's own class doc.
    // =============================================================================================

    /** One-time reconcile gate (engine retirement step 3): before EVER reading or writing
     * `service_records`/`maintenance_items` from this file, make sure any engine-only row has
     * already landed there. Cheap after the first call -
     * [EngineFleetServiceHistoryRetirementCopy.copyIfNeeded] itself short-circuits on its own
     * completion flag, so this is a SharedPreferences read on every later call, not a repeat scan.
     * **Unconditional, never gated on a "configured" check** - unlike places/pantry/ledger, fleet
     * has no configured/unconfigured split for these two record types at all (ticket 14: fleet is a
     * projection, legacy-primary always), so every entry point below calls this directly, matching
     * how [getByMac]/[getAll] above never gate on one either. Every public function in this section
     * (and [deleteVehicle] above, whose blockers now read these same tables) calls this first, so
     * none of them can read ahead of the copy regardless of call order. */
    private suspend fun ensureServiceHistoryReconciled(context: Context) {
        EngineFleetServiceHistoryRetirementCopy.copyIfNeeded(context)
    }

    /** Every `service_records` row (both kinds) for [mac] - the shared read [toItemsLegacy]/
     * [FleetRecordBridge.projectAnchorLegacy] group by service name, and every OBSERVED-only
     * accessor below filters. */
    private suspend fun allHistoryForVehicle(db: CarDatabase, mac: String): List<ServiceRecord> =
        db.serviceRecordDao().getRecordsForVehicleOnce(mac)

    /**
     * Best-effort THIRD write for one `service_records` row (backend-erp ticket 26 step 2,
     * `.scratch/backend-erp/issues/26-the-fleet-cutover-for-real.md`) - same role and same
     * never-throws, never-rolls-back-the-local-write posture as [syncVehicleToServer], see that
     * function's own doc comment for the full reasoning (a network failure here means the local
     * write already committed; failing loudly would report a local success as a total failure).
     *
     * **A car must already be synced before its service history can be** - [RemoteServiceHistory]'s
     * own doc comment says a `service_history` row can never upload ahead of its vehicle, and this
     * function has no way to synthesize a `vehicles.id` on the spot. A car with no
     * [VehicleSidecar] row yet (never synced, or the very first `syncVehicleToServer` for it is
     * still pending/failed) means this is a no-op, not a retryable error - the next successful
     * vehicle sync does not automatically retry this row, a named gap rather than a silent one.
     *
     * **Identity is [ServiceRecord.serverId]**, read off the row directly (co-located, not a
     * sidecar - see that field's own doc comment for why). `null` means insert; non-null means
     * PATCH by that uuid. On a fresh insert the returned uuid is written back onto the SAME local
     * row via [com.kevin.legion.data.local.ServiceRecordDao.setServerId], so a later edit of this
     * row updates the one server row it already has rather than minting a second.
     *
     * **Deletes are NOT pushed** (a real, out-loud scope decision, not an oversight): local
     * `softDeleteServiceRecord` stays exactly as documented - LOCAL ONLY, matching
     * [ServiceRecord.deleted]'s own doc comment - because [ServiceHistoryUpload] carries no
     * `deleted` field, mirroring [VehicleUpload]'s own identical lack of one. A server row for a
     * since-deleted local record keeps existing on Postgres/the replica until a future ticket
     * builds a real delete-sync path; a caller that already deleted the row locally simply never
     * calls this function again for it, so nothing here silently resurrects it either.
     */
    private suspend fun syncServiceHistoryToServer(context: Context, mac: String, localId: Long) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val record = db.serviceRecordDao().getById(localId) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(mac)?.serverId ?: return

        val remote = backend.upsertServiceHistory(
            ServiceHistoryUpload(
                serverId = record.serverId,
                vehicleServerId = vehicleServerId,
                serviceName = record.serviceName,
                mileage = record.mileage,
                serviceDateEpochMs = record.date,
                costCents = record.costCents,
                kind = record.kind,
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase service-history sync failed for record $localId: ${it.message}")
            return
        }

        if (record.serverId == null) {
            db.serviceRecordDao().setServerId(localId, remote.serverId)
        }
        db.serviceHistoryReplicaDao().upsert(
            ServiceHistoryReplica(
                serverId = remote.serverId,
                vehicleServerId = remote.vehicleServerId,
                serviceName = remote.serviceName,
                mileage = remote.mileage,
                serviceDateEpochMs = remote.serviceDateEpochMs,
                costCents = remote.costCents,
                kind = remote.kind,
                updatedAtMs = remote.updatedAtMs,
                deleted = remote.deleted,
                originGuid = remote.originGuid,
            ),
        )
    }

    /** All logged (OBSERVED) service records for [mac], newest first. */
    suspend fun serviceRecordsForVehicle(context: Context, mac: String): List<ServiceRecord> {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return allHistoryForVehicle(db, mac)
            .filter { it.kind == FleetAspectSeeder.KIND_OBSERVED }
            .sortedByDescending { it.date ?: 0L }
    }

    suspend fun getRecentForVehicle(context: Context, mac: String, limit: Int): List<ServiceRecord> =
        serviceRecordsForVehicle(context, mac).take(limit)

    suspend fun countForVehicle(context: Context, mac: String): Int = serviceRecordsForVehicle(context, mac).size

    suspend fun totalCostForVehicle(context: Context, mac: String): Long =
        serviceRecordsForVehicle(context, mac).sumOf { it.costCents ?: 0L }

    suspend fun countWithCostForVehicle(context: Context, mac: String): Int =
        serviceRecordsForVehicle(context, mac).count { it.costCents != null }

    suspend fun getServiceRecordById(context: Context, id: Long): ServiceRecord? {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        val record = db.serviceRecordDao().getById(id) ?: return null
        if (record.deleted || record.kind != FleetAspectSeeder.KIND_OBSERVED) return null
        return record
    }

    suspend fun mostRecentForVehicleAndService(context: Context, mac: String, serviceName: String): ServiceRecord? =
        serviceRecordsForVehicle(context, mac)
            .filter { it.serviceName == serviceName }
            .maxByOrNull { it.date ?: 0L }

    suspend fun hasRecordAtOrAfter(context: Context, mac: String, serviceName: String, atOrAfterMs: Long): Boolean =
        serviceRecordsForVehicle(context, mac).any { it.serviceName == serviceName && (it.date ?: 0L) >= atOrAfterMs }

    sealed class InsertObservedResult {
        /** [recordId] is now `service_records.id` (engine retirement step 3) - the name was
         * `engineRecordId` when this wrote an [EngineRecord]; nothing outside this file reads the
         * field by name (checked by grep before the rename), so the rename costs nothing. */
        data class Success(val recordId: Long) : InsertObservedResult()
        data class Failure(val reason: String) : InsertObservedResult()
    }

    /**
     * Writes a real, logged `ServiceHistory` row (`kind = OBSERVED`) into `service_records` and, in
     * the SAME transaction, supersedes any `ASSERTED` anchor for the same `(vehicleId, serviceName)`
     * this observation now explains (cutover instruction 3, carried through the engine retirement
     * repoint unchanged). **The both-axes rule is [FleetRecordBridge.explainedBy]**, applied here
     * against the LEGACY row's `mileage`/`date` instead of an [EngineRecord]'s payload fields - the
     * function signature is unchanged (`Int?`/`Long?` in, `Int?`/`Long?` out), so the rule itself did
     * not have to change, only what it reads. Every write path that logs a real, precise service -
     * voice `log_service`/`log_past_service` via [VehicleController], AND the hands-UI's
     * DONE_AT-with-cost save via `ui/fleet/MaintenanceWrites.kt` - calls this ONE function, so the
     * supersession fires identically no matter which surface logged the service.
     */
    suspend fun insertObserved(context: Context, mac: String, serviceName: String, mileage: Int, date: Long, costCents: Long?): InsertObservedResult {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.vehicleDao().getByMac(mac) == null) return InsertObservedResult.Failure("no vehicle on file for $mac")

        var outcome: InsertObservedResult = InsertObservedResult.Failure("write did not complete")
        try {
            db.withTransaction {
                val newId = db.serviceRecordDao().insertReturningId(
                    ServiceRecord(
                        vehicleId = mac, serviceName = serviceName, mileage = mileage, date = date, costCents = costCents,
                        kind = FleetAspectSeeder.KIND_OBSERVED, updatedAt = date,
                    ),
                )
                if (newId <= 0) throw EngineWriteFailedException("service_records insert did not return a row id")

                // ASSERTED supersession, in the same transaction as the OBSERVED insert.
                val assertedSyncId = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
                val asserted = db.serviceRecordDao().getBySyncId(assertedSyncId)
                if (asserted != null && !asserted.deleted) {
                    if (FleetRecordBridge.explainedBy(asserted.mileage, asserted.date, mileage, date)) {
                        db.serviceRecordDao().softDelete(asserted.id)
                    }
                }
                outcome = InsertObservedResult.Success(newId)
            }
        } catch (e: EngineWriteFailedException) {
            outcome = InsertObservedResult.Failure(e.reason)
        }
        if (outcome is InsertObservedResult.Success) {
            // Best-effort THIRD write, after the local insert (and any same-transaction ASSERTED
            // supersession) already committed - see syncServiceHistoryToServer's own doc comment.
            syncServiceHistoryToServer(context, mac, outcome.recordId)
        }
        return outcome
    }

    suspend fun editMileageAndCost(context: Context, id: Long, mileageMiles: Int, costCents: Long?): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        val written = db.serviceRecordDao().editMileageAndCost(id, mileageMiles, costCents)
        if (written > 0) {
            db.serviceRecordDao().getById(id)?.let { syncServiceHistoryToServer(context, it.vehicleId, id) }
        }
        return written
    }

    /** **LOCAL ONLY, and this stays true after ticket 26 step 2** - see [syncServiceHistoryToServer]'s
     * own doc comment for why a soft-delete is deliberately never pushed to the server: a delete-sync
     * path is a real, separate decision this ticket does not make. */
    suspend fun softDeleteServiceRecord(context: Context, id: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return db.serviceRecordDao().softDelete(id)
    }

    // =============================================================================================
    // MaintenanceSchedule (MaintenanceItem entity, anchor DERIVED from ServiceHistory - never
    // stored on this row, see this file's own class doc). Repointed off the engine at engine
    // retirement step 3.
    // =============================================================================================

    /** Composes the derived anchor onto every [schedules] row - the legacy-table equivalent of
     * [FleetRecordBridge.toMaintenanceItem]/[FleetRecordBridge.projectAnchor] against the engine.
     *
     * **Grouped and looked up by [FleetRecordBridge.serviceNameMatchKey], not the raw string
     * (one-today ticket 05, defect A).** `service_records.serviceName` and
     * `maintenance_items.serviceName` are independently free-typed, so `"Oil Change"` on one side
     * and `"Oil change"` on the other used to be two different map keys - the schedule row's history
     * lookup silently missed, `projectAnchorLegacy` was called with an empty list, and the item read
     * as permanently unknown with no error anywhere. [schedule]'s own display string is untouched;
     * only the bucketing key is folded. */
    private suspend fun toItemsLegacy(db: CarDatabase, mac: String, schedules: List<MaintenanceItem>): List<MaintenanceItem> {
        val byService = allHistoryForVehicle(db, mac).groupBy { FleetRecordBridge.serviceNameMatchKey(it.serviceName) }
        return schedules.map { schedule ->
            val (mileage, date) = FleetRecordBridge.projectAnchorLegacy(
                byService[FleetRecordBridge.serviceNameMatchKey(schedule.serviceName)].orEmpty()
            )
            schedule.copy(lastDoneMileage = mileage, lastDoneDate = date)
        }
    }

    suspend fun getForVehicle(context: Context, mac: String): List<MaintenanceItem> {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return toItemsLegacy(db, mac, db.maintenanceItemDao().getForVehicle(mac))
    }

    suspend fun getForVehicleIncludingDeleted(context: Context, mac: String): List<MaintenanceItem> {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return toItemsLegacy(db, mac, db.maintenanceItemDao().getForVehicleIncludingDeleted(mac))
    }

    suspend fun get(context: Context, mac: String, serviceName: String): MaintenanceItem? =
        getForVehicle(context, mac).firstOrNull { it.serviceName == serviceName }

    /** Genuine create only (a fresh schedule item, hand-added or the "no match" branch of a service
     * log) - never call this to edit an existing row, mirroring
     * [com.kevin.legion.data.local.MaintenanceItemDao.upsert]'s own "create only" contract.
     * [item]'s own `lastDoneMileage`/`lastDoneDate` are never stored on the schedule row itself (see
     * this file's own class doc) - if either is present, they seed an `ASSERTED` anchor in
     * `service_records` instead, the one place an anchor now lives. */
    suspend fun upsertNewItem(context: Context, item: MaintenanceItem) {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.vehicleDao().getByMac(item.vehicleId) == null) return
        db.maintenanceItemDao().upsert(item.copy(lastDoneMileage = null, lastDoneDate = null))
        if (item.lastDoneMileage != null || item.lastDoneDate != null) {
            writeAssertedAnchorLegacy(db, item.vehicleId, item.serviceName, item.lastDoneMileage, item.lastDoneDate, System.currentTimeMillis())
            syncAssertedAnchorToServer(context, item.vehicleId, item.serviceName)
        }
    }

    /** `-1L` on a collision with an existing `(vehicleId, serviceName)` pair, active OR trashed -
     * delegates straight to [com.kevin.legion.data.local.MaintenanceItemDao.insertIgnore], which
     * already carries this exact contract (a composite-PK `@Insert(IGNORE)` collides identically
     * whether the existing row is tombstoned or not). */
    suspend fun insertIgnore(context: Context, item: MaintenanceItem): Long {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.vehicleDao().getByMac(item.vehicleId) == null) return -1L
        return db.maintenanceItemDao().insertIgnore(item.copy(lastDoneMileage = null, lastDoneDate = null))
    }

    suspend fun setIntervals(context: Context, mac: String, serviceName: String, miles: Int?, months: Int?, source: String, now: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return db.maintenanceItemDao().setIntervals(mac, serviceName, miles, months, source, now)
    }

    /** Find-or-create-or-clear the deterministic `ASSERTED` row in `service_records` for
     * `(mac, serviceName)`, keyed on [FleetRecordBridge.assertedAnchorGuid] as that row's own
     * `syncId` - the legacy-table equivalent of the engine version's `writeAssertedAnchor`, unchanged
     * in every rule, only in what it writes to. A full-row REPLACE (via [ServiceRecordDao.insert]'s
     * `OnConflictStrategy.REPLACE` on `id`) both creates a fresh row AND "restores" a previously
     * soft-deleted one in one call - there is no separate restore-before-update step the engine
     * version needed, because SQLite REPLACE overwrites `deleted` along with everything else.
     *
     * **CORRECTED ticket 26 step 2: the REPLACE now carries [existing]'s `serverId` forward.**
     * Before this row gained a `serverId` column, a REPLACE that rebuilt the whole row from scratch
     * cost nothing extra; now it would silently wipe the row's link to its own server counterpart on
     * every single edit, forcing [syncServiceHistoryToServer] to mint a brand new server row each
     * time a driver corrects an anchor - exactly the kind of identity churn ticket 26's own "RULED"
     * section exists to prevent, just one layer further in. No `context`/network call happens here -
     * this runs inside [setAnchor]/[upsertNewItem]'s `db.withTransaction` block, and a network call
     * has no business inside a Room transaction; each caller pushes to the server itself, after the
     * transaction commits, using the id this function leaves behind. */
    private suspend fun writeAssertedAnchorLegacy(db: CarDatabase, mac: String, serviceName: String, mileage: Int?, date: Long?, now: Long) {
        val syncId = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
        val existing = db.serviceRecordDao().getBySyncId(syncId)
        if (mileage == null && date == null) {
            // "I don't know" clears any existing ASSERTED row to unknown - soft-delete rather than
            // writing a meaningless null/null row (there is nothing left for such a row to assert).
            if (existing != null && !existing.deleted) db.serviceRecordDao().softDelete(existing.id)
            return
        }
        db.serviceRecordDao().insert(
            ServiceRecord(
                id = existing?.id ?: 0, vehicleId = mac, serviceName = serviceName, mileage = mileage, date = date,
                costCents = null, syncId = syncId, deleted = false, kind = FleetAspectSeeder.KIND_ASSERTED, updatedAt = now,
                serverId = existing?.serverId,
            ),
        )
    }

    /** Resolves the `ASSERTED` row [writeAssertedAnchorLegacy] just wrote (or left cleared) for
     * `(mac, serviceName)` and pushes it to the server if it still exists - the after-the-transaction
     * half of that function's own doc comment. A no-op when the anchor was cleared (soft-deleted,
     * per [syncServiceHistoryToServer]'s own "deletes are not pushed" rule) or never existed. */
    private suspend fun syncAssertedAnchorToServer(context: Context, mac: String, serviceName: String) {
        val db = CarDatabase.getDatabase(context)
        val syncId = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
        val row = db.serviceRecordDao().getBySyncId(syncId) ?: return
        if (row.deleted) return
        syncServiceHistoryToServer(context, mac, row.id)
    }

    /**
     * "When was this last done", never the interval. Writes/clears the deterministic `ASSERTED`
     * `service_records` row for `(mac, serviceName)` and clears `MaintenanceSchedule.neverDone` back
     * to false (supplying a real anchor is the driver un-confirming a prior "never done"). Returns 0
     * (no-op, per ticket 05's law) when no active `MaintenanceSchedule` row exists for this pair.
     */
    suspend fun setAnchor(context: Context, mac: String, serviceName: String, mileage: Int?, date: Long?, now: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.maintenanceItemDao().get(mac, serviceName) == null) return 0

        db.withTransaction {
            db.maintenanceItemDao().clearNeverDone(mac, serviceName, now)
            writeAssertedAnchorLegacy(db, mac, serviceName, mileage, date, now)
        }
        syncAssertedAnchorToServer(context, mac, serviceName)
        return 1
    }

    /**
     * Clears `MaintenanceSchedule.neverDone` back to false ONLY - never touches the anchor.
     * [insertObserved] already IS the new anchor the instant it lands (the projected mileage/date -
     * [FleetRecordBridge.projectAnchorLegacy] - reads straight off the `OBSERVED` row just written),
     * so [VehicleController.logServiceDirect]'s matched-item branch calls this rather than
     * [setAnchor]. Returns 0 (no-op, ticket 05's law) when no active row exists for this pair.
     */
    suspend fun setNeverDoneCleared(context: Context, mac: String, serviceName: String, now: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.maintenanceItemDao().get(mac, serviceName) == null) return 0
        return db.maintenanceItemDao().clearNeverDone(mac, serviceName, now)
    }

    /** Marks `neverDone` and clears the anchor - "never done" REPLACES any prior guess, mirroring
     * [com.kevin.legion.data.local.MaintenanceItemDao.setNeverDone]'s exact contract. */
    suspend fun setNeverDone(context: Context, mac: String, serviceName: String, now: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        if (db.maintenanceItemDao().get(mac, serviceName) == null) return 0

        db.withTransaction {
            db.maintenanceItemDao().setNeverDone(mac, serviceName, now)
            val assertedSyncId = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
            val asserted = db.serviceRecordDao().getBySyncId(assertedSyncId)
            if (asserted != null && !asserted.deleted) db.serviceRecordDao().softDelete(asserted.id)
        }
        return 1
    }

    /** Tombstones the `MaintenanceSchedule` record only - `ServiceHistory` (both `OBSERVED` and any
     * `ASSERTED` row) survives untouched. */
    suspend fun softDeleteItem(context: Context, mac: String, serviceName: String, now: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return db.maintenanceItemDao().softDelete(mac, serviceName, now)
    }

    /** Un-tombstones a row AND sets its interval in one call - the populate diff's "you deleted this
     * - add it back?" case. No-op contract per [com.kevin.legion.data.local.MaintenanceItemDao]'s
     * own doc: a restore against a pair that was never tombstoned (or never existed) touches nothing. */
    suspend fun restore(context: Context, mac: String, serviceName: String, miles: Int?, months: Int?, source: String, now: Long): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        val existing = db.maintenanceItemDao().getForVehicleIncludingDeleted(mac).firstOrNull { it.serviceName == serviceName } ?: return 0
        if (!existing.deleted) return 0 // was never tombstoned - restore is a no-op by contract
        return db.maintenanceItemDao().restore(mac, serviceName, miles, months, source, now)
    }

    // =============================================================================================
    // Drive (backend-erp ticket 26 step 3) - no replica, no read-side repoint needed. See this
    // file's own class doc for why.
    // =============================================================================================

    /**
     * Writes a new, finalised [Drive] row for [mac] and, best-effort, pushes it to the server.
     * [com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive] calls this now instead of
     * `db.driveDao().insert` directly - the one caller that creates drives, so there is exactly one
     * seam to keep in sync with the push.
     *
     * A drive is never edited after this call (`Drive`'s own class doc: "no update, no delete"), so
     * unlike [insertObserved] there is no in-transaction supersession step - this is a plain insert
     * followed by [syncDriveToServer], mirroring [syncVehicleToServer]'s own "local write already
     * committed, the server push is a best-effort third write" posture.
     */
    suspend fun recordDrive(
        context: Context,
        mac: String,
        startedAt: Long,
        endedAt: Long,
        miles: Double,
        gallons: Double?,
        endReason: String,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val id = db.driveDao().insert(
            Drive(
                vehicleId = mac,
                startedAt = startedAt,
                endedAt = endedAt,
                miles = miles,
                gallons = gallons,
                endReason = endReason,
            ),
        )
        syncDriveToServer(context, mac, id)
        return id
    }

    /**
     * Best-effort push of one `drives` row, keyed on [Drive.syncId] - never throws, never rolls back
     * the local write, same posture as [syncServiceHistoryToServer] (see that function's own doc
     * comment for the full reasoning). **A car must already be synced first** - same "no way to
     * synthesize a `vehicles.id` on the spot" constraint as [syncServiceHistoryToServer]; a car with
     * no [VehicleSidecar] row yet makes this a no-op, not a retryable error.
     *
     * **`internal`, not `private`** - unlike `service_records`, a `Drive` is never edited, so there
     * is no domain-level "edit" call this function can piggyback a re-run test on the way
     * [editMileageAndCost] lets [FleetEngineStoreServiceHistoryCutoverTest] exercise
     * [syncServiceHistoryToServer] twice. Exposing this at module visibility lets the test drive a
     * genuine retry of the SAME local row directly, which is the only way to exercise "a re-run does
     * not remint the local row id" for a table with no edit path at all.
     */
    internal suspend fun syncDriveToServer(context: Context, mac: String, localId: Long) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val drive = db.driveDao().getById(localId) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(mac)?.serverId ?: return

        val remote = backend.upsertDrive(
            DriveUpload(
                syncId = drive.syncId,
                vehicleServerId = vehicleServerId,
                startedAtMs = drive.startedAt,
                endedAtMs = drive.endedAt,
                miles = drive.miles,
                gallons = drive.gallons,
                endReason = drive.endReason,
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase drive sync failed for drive $localId: ${it.message}")
            return
        }

        if (drive.serverId == null) {
            db.driveDao().setServerId(localId, remote.serverId)
        }
    }

    // =============================================================================================
    // DriveReassignment (backend-erp ticket 26 step 3) - built in the same change as Drive per
    // ticket 06's ruling: a fact and its corrections must not split across two systems.
    // =============================================================================================

    /**
     * Records a "this drive belongs to another car" correction, applies it to `obd_samples`
     * immediately (unchanged local behaviour, moved here from
     * [com.kevin.legion.vehicle.VehicleController.reassignDrive] verbatim), and, best-effort, pushes
     * the rule to the server. Returns `null` on the no-op self-correction case
     * ([fromVehicleId] == [toVehicleId]) - matching [VehicleController.reassignDrive]'s own early
     * return, now made an explicit result instead of a silent void.
     *
     */
    suspend fun recordDriveReassignment(
        context: Context,
        fromVehicleId: String,
        toVehicleId: String,
        fromMs: Long,
        toMs: Long,
    ): Long? {
        if (fromVehicleId == toVehicleId) return null
        val db = CarDatabase.getDatabase(context)
        val id = db.driveReassignmentDao().insert(
            DriveReassignment(
                syncId = java.util.UUID.randomUUID().toString(),
                vehicleId = fromVehicleId,
                fromMs = fromMs,
                toMs = toMs,
                newVehicleId = toVehicleId,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        db.openHelper.writableDatabase.execSQL(
            "UPDATE `obd_samples` SET `vehicleId` = ? WHERE `vehicleId` = ? AND `timestamp` BETWEEN ? AND ?",
            arrayOf(toVehicleId, fromVehicleId, fromMs, toMs),
        )
        syncDriveReassignmentToServer(context, id)
        return id
    }

    /**
     * Best-effort push of one `drive_reassignments` row, keyed on [DriveReassignment.syncId] - same
     * never-throws, never-rolls-back-the-local-write posture as [syncDriveToServer]. **Both cars
     * named by the rule must already be synced** - resolves `vehicleServerId` (the car the window is
     * currently attributed to) AND `newVehicleServerId` (the car it should move to) through
     * [VehicleSidecar], and a rule naming either car before it has synced is a no-op push, not a
     * retryable error, same shape as [syncServiceHistoryToServer]'s single-car resolution.
     *
     * `internal` for the identical reason [syncDriveToServer] is - a [DriveReassignment] is never
     * edited by a domain call either, so a direct re-run is the only way to test push idempotency.
     */
    internal suspend fun syncDriveReassignmentToServer(context: Context, localId: Long) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val reassignment = db.driveReassignmentDao().getById(localId) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(reassignment.vehicleId)?.serverId ?: return
        val newVehicleServerId = db.vehicleSidecarDao().getByMac(reassignment.newVehicleId)?.serverId ?: return

        val remote = backend.upsertDriveReassignment(
            DriveReassignmentUpload(
                syncId = reassignment.syncId,
                vehicleServerId = vehicleServerId,
                newVehicleServerId = newVehicleServerId,
                fromAtMs = reassignment.fromMs,
                toAtMs = reassignment.toMs,
                provenance = "USER",
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase drive-reassignment sync failed for row $localId: ${it.message}")
            return
        }

        if (reassignment.serverId == null) {
            db.driveReassignmentDao().setServerId(localId, remote.serverId)
        }
    }

    // =============================================================================================
    // CodeEvent (backend-erp ticket 26 step 4) - same "no replica, no read-side repoint" shape as
    // Drive/DriveReassignment above: `code_events` has no engine-record counterpart and its own id
    // has no reader outside its own DAO, so a configured read needs no change at all. See this
    // file's own class doc for the two-of-three scoping this step landed with.
    // =============================================================================================

    /**
     * Writes a new [CodeEvent] row for [mac] and, best-effort, pushes it to the server.
     * [com.kevin.legion.service.AriaForegroundService.recordCodeEvent] calls this now instead of
     * `db.codeEventDao().insert` directly - the one place a code event is created, so there is
     * exactly one seam to keep in sync with the push. Mirrors [recordDrive]'s own shape: a plain
     * insert followed by a best-effort third write, no in-transaction step needed because a code
     * event, like a drive, is never edited after it is written.
     */
    suspend fun recordCodeEvent(
        context: Context,
        mac: String,
        timestamp: Long,
        mileage: Int?,
        codesJson: String,
        freezeFrameJson: String,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val id = db.codeEventDao().insert(
            CodeEvent(
                vehicleId = mac,
                timestamp = timestamp,
                mileage = mileage,
                codesJson = codesJson,
                freezeFrameJson = freezeFrameJson,
            ),
        )
        syncCodeEventToServer(context, mac, id)
        return id
    }

    /**
     * Best-effort push of one `code_events` row, keyed on [CodeEvent.syncId] - never throws, never
     * rolls back the local write, same posture as [syncDriveToServer] (see that function's own doc
     * comment for the full reasoning). **A car must already be synced first** - same "no way to
     * synthesize a `vehicles.id` on the spot" constraint as [syncDriveToServer]; a car with no
     * [VehicleSidecar] row yet makes this a no-op, not a retryable error.
     *
     * Provenance is always `"DETERMINISTIC"` here - a dongle produced these codes, no person
     * transcribed anything (CLAUDE.md section 4 rule 4; see [CodeEventUpload]'s own doc comment for
     * why this is asserted explicitly rather than left to a column default).
     *
     * **`internal`, not `private`** - same reasoning as [syncDriveToServer]: a [CodeEvent] has no
     * domain-level edit call to piggyback a re-run test on, so the test drives a genuine retry of
     * the same local row directly through this function.
     */
    internal suspend fun syncCodeEventToServer(context: Context, mac: String, localId: Long) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val event = db.codeEventDao().getById(localId) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(mac)?.serverId ?: return

        val remote = backend.upsertCodeEvent(
            CodeEventUpload(
                syncId = event.syncId,
                vehicleServerId = vehicleServerId,
                occurredAtMs = event.timestamp,
                mileage = event.mileage,
                codesJson = event.codesJson,
                freezeFrameJson = event.freezeFrameJson.ifEmpty { null },
                provenance = "DETERMINISTIC",
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase code-event sync failed for event $localId: ${it.message}")
            return
        }

        if (event.serverId == null) {
            db.codeEventDao().setServerId(localId, remote.serverId)
        }
    }

    // =============================================================================================
    // CodeClearEvent (backend-erp ticket 26 step 4) - same shape as CodeEvent above.
    // =============================================================================================

    /**
     * Writes a new [CodeClearEvent] row (for the three outcomes that ever earn one - see
     * [CodeClearEvent]'s own class doc) and, best-effort, pushes it to the server.
     * [com.kevin.legion.vehicle.DtcClearController.recordOutcome] calls this now instead of
     * `db.codeClearEventDao().insert` directly - the one place this row is created.
     */
    suspend fun recordCodeClearEvent(
        context: Context,
        mac: String,
        timestamp: Long,
        mileage: Int?,
        codesBeforeJson: String,
        freezeFrameJson: String,
        codesAfterJson: String,
        outcome: String,
        ackRaw: String,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val id = db.codeClearEventDao().insert(
            CodeClearEvent(
                vehicleId = mac,
                timestamp = timestamp,
                mileage = mileage,
                codesBeforeJson = codesBeforeJson,
                freezeFrameJson = freezeFrameJson,
                codesAfterJson = codesAfterJson,
                outcome = outcome,
                ackRaw = ackRaw,
            ),
        )
        syncCodeClearEventToServer(context, mac, id)
        return id
    }

    /**
     * Best-effort push of one `code_clear_events` row, keyed on [CodeClearEvent.syncId] - same
     * never-throws, never-rolls-back-the-local-write posture as [syncCodeEventToServer]. Provenance
     * is always `"DETERMINISTIC"` here too - the codes-before/after snapshots come straight off the
     * ECU, matching [CodeClearEventUpload]'s own doc comment. `internal` for the identical reason
     * [syncCodeEventToServer] is - no domain-level edit call exists to piggyback a retry test on.
     */
    internal suspend fun syncCodeClearEventToServer(context: Context, mac: String, localId: Long) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val event = db.codeClearEventDao().getById(localId) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(mac)?.serverId ?: return

        val remote = backend.upsertCodeClearEvent(
            CodeClearEventUpload(
                syncId = event.syncId,
                vehicleServerId = vehicleServerId,
                occurredAtMs = event.timestamp,
                mileage = event.mileage,
                codesBeforeJson = event.codesBeforeJson,
                freezeFrameJson = event.freezeFrameJson.ifEmpty { null },
                codesAfterJson = event.codesAfterJson.ifEmpty { null },
                outcome = event.outcome,
                ackRaw = event.ackRaw,
                provenance = "DETERMINISTIC",
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase code-clear-event sync failed for event $localId: ${it.message}")
            return
        }

        if (event.serverId == null) {
            db.codeClearEventDao().setServerId(localId, remote.serverId)
        }
    }

    // =============================================================================================
    // VehicleSpec (backend-erp ticket 26 step 5, the last one) - no replica, no read-side repoint,
    // and no bookkeeping serverId column either. See this file's own class doc for the full
    // reasoning: [VehicleSpec.vehicleId] IS the natural key both locally and (via
    // [VehicleSidecar.serverId]) on the server, so there is no separate row id to remember.
    // =============================================================================================

    /**
     * Upserts a [VehicleSpec] row locally and, best-effort, pushes it to the server - the single
     * seam [com.kevin.legion.vehicle.VehicleSpecController.refreshFromVin]/`saveManual` now call
     * instead of `db.vehicleSpecDao().upsert` directly, mirroring [recordDrive]'s "the facade
     * writes local then pushes" shape. Unlike every prior table in this step, there is no
     * insert-vs-update branch to make - [VehicleSpecUpload] is a genuine REPLACE-on-conflict
     * (matching [com.kevin.legion.data.local.VehicleSpecDao.upsertStamped]'s own local REPLACE
     * semantics), so the local write and the push both simply overwrite every column every time.
     */
    suspend fun upsertVehicleSpec(context: Context, spec: VehicleSpec) {
        val db = CarDatabase.getDatabase(context)
        db.vehicleSpecDao().upsert(spec)
        syncVehicleSpecToServer(context, spec.vehicleId)
    }

    /**
     * Best-effort push of one `vehicle_specs` row, keyed on its own `vehicle_id` (the same uuid
     * [VehicleSidecar.serverId] already maps [mac] to) - never throws, never rolls back the local
     * write, same posture as [syncServiceHistoryToServer]. **A car must already be synced first**
     * - same "no way to synthesize a `vehicles.id` on the spot" constraint as every other syncer in
     * this file; a car with no [VehicleSidecar] row yet makes this a no-op, not a retryable error.
     *
     * Provenance is always `"DETERMINISTIC"` - mostly a machine VIN decode via vPIC, the three
     * manual paint/notes columns notwithstanding (matching [VehicleSpecUpload]'s own doc comment
     * and [com.kevin.legion.backend.FleetReconcile]'s identical assertion for this table).
     * `decodedAtMs` crosses the wire as a real `null` for "never decoded" rather than the phone's
     * `0L` sentinel - [VehicleSpec.decodedAt]'s own doc comment, same translation
     * [com.kevin.legion.backend.FleetReconcile] already performs for this exact field.
     *
     * `internal`, not `private` - a [VehicleSpec] has no domain-level "edit" call distinct from a
     * fresh upsert (every write IS a full overwrite), so the test drives a genuine retry of the
     * same local row directly through this function, same reasoning as [syncDriveToServer].
     */
    internal suspend fun syncVehicleSpecToServer(context: Context, mac: String) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val spec = db.vehicleSpecDao().get(mac) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(mac)?.serverId ?: return

        backend.upsertVehicleSpec(
            VehicleSpecUpload(
                vehicleServerId = vehicleServerId,
                vin = spec.vin,
                engineCylinders = spec.engineCylinders,
                displacementL = spec.displacementL,
                engineHp = spec.engineHp,
                engineConfig = spec.engineConfig,
                fuelType = spec.fuelType,
                transmissionStyle = spec.transmissionStyle,
                transmissionSpeeds = spec.transmissionSpeeds,
                driveType = spec.driveType,
                bodyClass = spec.bodyClass,
                doors = spec.doors,
                series = spec.series,
                vehicleType = spec.vehicleType,
                manufacturer = spec.manufacturer,
                plantCity = spec.plantCity,
                plantCountry = spec.plantCountry,
                paintColor = spec.paintColor,
                paintCode = spec.paintCode,
                buildNotes = spec.buildNotes,
                decodedAtMs = spec.decodedAt.takeIf { it != 0L },
                provenance = "DETERMINISTIC",
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase vehicle-spec sync failed for $mac: ${it.message}")
        }
        // No local write-back: unlike every syncId-keyed table above, there is no bookkeeping
        // serverId column here to set - see this section's own class doc.
    }

    // =============================================================================================
    // BuildEntry (backend-erp ticket 26 step 5) - same "no replica, no read-side repoint" shape as
    // Drive/CodeEvent: `build_entries` has no engine-record counterpart and its own local `id` has
    // no reader outside its own DAO (checked: `FleetScreen`/`BuildSheetController` read by
    // `vehicleId`/`type`, never by `id`), so a configured read needs no change at all.
    // =============================================================================================

    /**
     * Writes a new [BuildEntry] row for [mac] and, best-effort, pushes it to the server.
     * [com.kevin.legion.vehicle.BuildSheetController.add] calls this now instead of
     * `db.buildEntryDao().insert` directly - the one caller that creates a build-sheet line, so
     * there is exactly one seam to keep in sync with the push. A build entry, like a [Drive], is
     * never edited after this call ([BuildEntryDao]'s own doc comment on why `delete` is dormant),
     * so this is a plain insert followed by [syncBuildEntryToServer], mirroring [recordDrive]'s own
     * shape exactly.
     */
    suspend fun recordBuildEntry(
        context: Context,
        mac: String,
        type: String,
        title: String,
        vendor: String,
        partNumber: String,
        cost: Double?,
        date: Long,
        mileage: Int?,
        notes: String,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val id = db.buildEntryDao().insert(
            BuildEntry(
                vehicleId = mac,
                type = type,
                title = title,
                vendor = vendor,
                partNumber = partNumber,
                cost = cost,
                date = date,
                mileage = mileage,
                notes = notes,
            ),
        )
        syncBuildEntryToServer(context, mac, id)
        return id
    }

    /** [BuildEntry.cost] is `Double` dollars; `build_entries.cost_cents` is `Long` cents. Rounds to
     * the nearest cent rather than truncating, same reasoning and same one-line choice as
     * [com.kevin.legion.backend.FleetReconcile]'s own private copy of this exact conversion (that
     * object's own doc comment on the rounding decision applies verbatim here; duplicated rather
     * than shared because that copy is `private` to a different object and this cutover does not
     * own a reason to widen its visibility). */
    private fun dollarsToCentsOrNull(dollars: Double?): Long? = dollars?.let { Math.round(it * 100.0) }

    /**
     * Best-effort push of one `build_entries` row, keyed on [BuildEntry.syncId] - never throws,
     * never rolls back the local write, same posture as [syncDriveToServer] (see that function's
     * own doc comment for the full reasoning). **A car must already be synced first** - same "no
     * way to synthesize a `vehicles.id` on the spot" constraint as every other syncer in this file;
     * a car with no [VehicleSidecar] row yet makes this a no-op, not a retryable error.
     *
     * Provenance is always `"USER"` here - a driver-authored logbook line, matching
     * [BuildEntryUpload]'s own doc comment and [com.kevin.legion.backend.FleetReconcile]'s
     * identical assertion for this table.
     *
     * **`internal`, not `private`** - same reasoning as [syncDriveToServer]: a [BuildEntry] has no
     * domain-level edit call to piggyback a re-run test on, so the test drives a genuine retry of
     * the same local row directly through this function.
     */
    internal suspend fun syncBuildEntryToServer(context: Context, mac: String, localId: Long) {
        val backend = backend(context) ?: return
        val db = CarDatabase.getDatabase(context)
        val entry = db.buildEntryDao().getById(localId) ?: return
        val vehicleServerId = db.vehicleSidecarDao().getByMac(mac)?.serverId ?: return

        val remote = backend.upsertBuildEntry(
            BuildEntryUpload(
                syncId = entry.syncId,
                vehicleServerId = vehicleServerId,
                entryType = entry.type,
                title = entry.title,
                vendor = entry.vendor,
                partNumber = entry.partNumber,
                costCents = dollarsToCentsOrNull(entry.cost),
                loggedAtMs = entry.date,
                mileage = entry.mileage,
                notes = entry.notes,
                provenance = "USER",
            ),
        ).getOrElse {
            Log.w("FleetEngineStore", "Supabase build-entry sync failed for entry $localId: ${it.message}")
            return
        }

        if (entry.serverId == null) {
            db.buildEntryDao().setServerId(localId, remote.serverId)
        }
    }
}
