package com.kevin.legion.vehicle

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseFleetBackend
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleReplica
import com.kevin.legion.data.local.VehicleSidecar
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
        return outcome
    }

    suspend fun editMileageAndCost(context: Context, id: Long, mileageMiles: Int, costCents: Long?): Int {
        ensureServiceHistoryReconciled(context)
        val db = CarDatabase.getDatabase(context)
        return db.serviceRecordDao().editMileageAndCost(id, mileageMiles, costCents)
    }

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
     * [FleetRecordBridge.toMaintenanceItem]/[FleetRecordBridge.projectAnchor] against the engine. */
    private suspend fun toItemsLegacy(db: CarDatabase, mac: String, schedules: List<MaintenanceItem>): List<MaintenanceItem> {
        val byService = allHistoryForVehicle(db, mac).groupBy { it.serviceName }
        return schedules.map { schedule ->
            val (mileage, date) = FleetRecordBridge.projectAnchorLegacy(byService[schedule.serviceName].orEmpty())
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
     * version needed, because SQLite REPLACE overwrites `deleted` along with everything else. */
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
            ),
        )
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
}
