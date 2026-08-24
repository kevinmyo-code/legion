package com.kevin.legion.vehicle

import android.content.Context
import androidx.room.withTransaction
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import org.json.JSONObject

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
 * **`ServiceHistory`/`MaintenanceSchedule` have no such problem** - their engine records are the
 * ONLY store from this branch forward (see the cutover doc's ruling table: zero legacy writers on
 * `service_records`/`maintenance_items`), and [MaintenanceItem.lastDoneMileage]/[lastDoneDate] are
 * reconstructed fresh on every read via [FleetRecordBridge.toMaintenanceItem]/[FleetRecordBridge.projectAnchor] -
 * this is the actual unification ticket 29 asked for; the Vehicle-mirror question above is a
 * narrower, purely-identity-shaped problem the carve's own guid derivation created, unrelated to
 * ticket 29's drift bug (Vehicle identity was never split across two stores the way service history
 * was).
 */
object FleetEngineStore {

    /** Thrown only to force [androidx.room.withTransaction] to roll back a whole multi-step fleet
     * write - same primitive `ledger/IngestPipeline.kt`/`pantry/PantryController.kt` already use. */
    private class EngineWriteFailedException(val reason: String) : Exception()

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    private suspend fun engineVehicleId(db: CarDatabase, mac: String): Long? =
        db.engineRecordDao().getByGuid(FleetRecordBridge.vehicleGuid(mac))?.id

    // =============================================================================================
    // Vehicle
    // =============================================================================================

    suspend fun getByMac(context: Context, mac: String): Vehicle? =
        CarDatabase.getDatabase(context).vehicleDao().getByMac(mac)

    /** Active (non-archived) cars only - see [com.kevin.legion.data.local.VehicleDao.getAll]'s own doc. */
    suspend fun getAll(context: Context): List<Vehicle> =
        CarDatabase.getDatabase(context).vehicleDao().getAll()

    suspend fun getAllIncludingArchived(context: Context): List<Vehicle> =
        CarDatabase.getDatabase(context).vehicleDao().getAllIncludingArchived()

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
        return 1
    }

    suspend fun setArchived(context: Context, mac: String, archived: Boolean, now: Long) {
        // archived is a legacy-only field (wave4-carve's own ruling) - the mirror is the sole store
        // for it, same as tripMilesSinceBaseline/lastOdometerPromptAt. No engine write: nothing on
        // the engine Vehicle record type represents "hidden from the roster", and inventing a field
        // for it now would re-litigate a carve decision this cutover is bound by, not empowered to
        // reopen (wave4-carve: "revisited only if a follow-up wave's cutover finds a live need").
        CarDatabase.getDatabase(context).vehicleDao().setArchived(mac, archived, now)
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
        return 1
    }

    /** Legacy-only column (nag cadence) - see this file's own class doc. */
    suspend fun markOdometerPrompted(context: Context, mac: String, at: Long, now: Long) {
        CarDatabase.getDatabase(context).vehicleDao().markOdometerPrompted(mac, at, now)
    }

    /** Legacy-only sentinel cleanup - see [com.kevin.legion.data.local.VehicleDao.clearThisCarSentinel]'s own doc. */
    suspend fun clearThisCarSentinel(context: Context, now: Long) {
        CarDatabase.getDatabase(context).vehicleDao().clearThisCarSentinel(now)
    }

    /**
     * Trashes the engine `Vehicle` record - exercises [RecordStore]'s `DeletePolicy.BLOCK` on
     * `ServiceHistory.vehicle`/`MaintenanceSchedule.vehicle` (`docs/architecture/wave4-carve-2026-08-23.md`'s
     * own field mapping). **No live caller reaches this today** - archive/unarchive
     * ([setArchived]) is the only removal affordance the app currently exposes for a car; a real
     * hard-delete voice tool or screen action does not exist, so this exists to make the refusal
     * mechanically real and testable (instruction 5: "exercised directly against a real RecordStore")
     * rather than to back a live capability this branch did not add. When a caller does reach it, the
     * contract is: [RecordStore.DeleteResult.Blocked] means NOTHING was written, and the caller must
     * surface `blockers` in words - "that car has service history on file, so I can't delete it" -
     * never a silent no-op and never a partial delete.
     */
    suspend fun deleteVehicle(context: Context, mac: String): RecordStore.DeleteResult {
        val db = CarDatabase.getDatabase(context)
        val recordStore = store(db)
        val engineId = engineVehicleId(db, mac) ?: return RecordStore.DeleteResult.NotFound
        return recordStore.delete(engineId)
    }

    // =============================================================================================
    // ServiceHistory (ServiceRecord value object, OBSERVED rows only - ASSERTED rows never surface
    // as a ServiceRecord; they have no real event behind them)
    // =============================================================================================

    private suspend fun activeServiceHistoryForVehicle(db: CarDatabase, schema: FleetAspectSeeder.Schema, vehicleEngineId: Long): List<EngineRecord> =
        db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId)
            .filter { FleetRecordBridge.serviceHistoryVehicleId(it, schema.serviceHistory.fieldIds) == vehicleEngineId }

    /** All logged (OBSERVED) service records for [mac], newest first - the engine-backed equivalent
     * of [com.kevin.legion.data.local.ServiceRecordDao.getRecordsForVehicle]'s `Flow`, collected to a
     * plain `List` since every caller of that Flow already only ever read `.first()` off it (FleetScreen,
     * FleetSpendController) - there was never a second subscriber relying on live updates. */
    suspend fun serviceRecordsForVehicle(context: Context, mac: String): List<ServiceRecord> {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val engineId = engineVehicleId(db, mac) ?: return emptyList()
        return activeServiceHistoryForVehicle(db, schema, engineId)
            .filter { FleetRecordBridge.kindOf(it, schema.serviceHistory.fieldIds) == FleetAspectSeeder.KIND_OBSERVED }
            .map { FleetRecordBridge.toServiceRecord(it, schema.serviceHistory.fieldIds).copy(vehicleId = mac) }
            .sortedByDescending { it.date }
    }

    suspend fun getRecentForVehicle(context: Context, mac: String, limit: Int): List<ServiceRecord> =
        serviceRecordsForVehicle(context, mac).take(limit)

    suspend fun countForVehicle(context: Context, mac: String): Int = serviceRecordsForVehicle(context, mac).size

    suspend fun totalCostForVehicle(context: Context, mac: String): Long =
        serviceRecordsForVehicle(context, mac).sumOf { it.costCents ?: 0L }

    suspend fun countWithCostForVehicle(context: Context, mac: String): Int =
        serviceRecordsForVehicle(context, mac).count { it.costCents != null }

    suspend fun getServiceRecordById(context: Context, id: Long): ServiceRecord? {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().getById(id) ?: return null
        if (record.recordTypeId != schema.serviceHistory.recordTypeId || record.deletedAt != null) return null
        if (FleetRecordBridge.kindOf(record, schema.serviceHistory.fieldIds) != FleetAspectSeeder.KIND_OBSERVED) return null
        // vehicleId (mac) is not recoverable from the engine record alone (Vehicle's own guid
        // problem, one level removed) - the one caller of this function
        // (VehicleController.editServiceRecordDirect's read-back) only ever uses the returned
        // ServiceRecord's serviceName/mileage/date/costCents, never its vehicleId, so this is left
        // blank rather than threading an extra vehicle lookup nothing needs.
        return FleetRecordBridge.toServiceRecord(record, schema.serviceHistory.fieldIds)
    }

    suspend fun mostRecentForVehicleAndService(context: Context, mac: String, serviceName: String): ServiceRecord? =
        serviceRecordsForVehicle(context, mac)
            .filter { it.serviceName == serviceName }
            .maxByOrNull { it.date }

    suspend fun hasRecordAtOrAfter(context: Context, mac: String, serviceName: String, atOrAfterMs: Long): Boolean =
        serviceRecordsForVehicle(context, mac).any { it.serviceName == serviceName && it.date >= atOrAfterMs }

    sealed class InsertObservedResult {
        data class Success(val engineRecordId: Long) : InsertObservedResult()
        data class Failure(val reason: String) : InsertObservedResult()
    }

    /**
     * Writes a real, logged `ServiceHistory` row (`kind = OBSERVED`) and, in the SAME transaction,
     * supersedes any `ASSERTED` anchor for the same `(vehicleId, serviceName)` this observation now
     * explains (cutover instruction 3, `docs/architecture/wave4-carve-2026-08-23.md`'s owed follow-up
     * #8). **The both-axes rule is [FleetRecordBridge.explainedBy] - identical to the migration's own
     * corrected dedup rule**, so a post-cutover log can supersede an anchor the migration itself wrote
     * (same deterministic guid, [FleetRecordBridge.assertedAnchorGuid]) exactly as readily as one a
     * post-cutover `setAnchor` call wrote. Every write path that logs a real, precise service - voice
     * `log_service`/`log_past_service`(with a date) via [VehicleController], AND the hands-UI's
     * DONE_AT-with-cost save via `ui/fleet/MaintenanceWrites.kt` - calls this ONE function, so the
     * supersession fires identically no matter which surface logged the service (the unification this
     * cutover exists to land, extended to the supersession rule itself).
     */
    suspend fun insertObserved(context: Context, mac: String, serviceName: String, mileage: Int, date: Long, costCents: Long?): InsertObservedResult {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val vehicleEngineId = engineVehicleId(db, mac)
            ?: return InsertObservedResult.Failure("no vehicle on file for $mac")

        var outcome: InsertObservedResult = InsertObservedResult.Failure("write did not complete")
        try {
            db.withTransaction {
                val record = ServiceRecord(vehicleId = mac, serviceName = serviceName, mileage = mileage, date = date, costCents = costCents)
                val created = recordStore.create(
                    recordTypeId = schema.serviceHistory.recordTypeId,
                    fieldValues = FleetRecordBridge.observedFieldValues(record, schema.serviceHistory.fieldIds, vehicleEngineId),
                    provenance = RecordProvenance.USER,
                    now = date,
                )
                if (created !is RecordStore.WriteResult.Success) {
                    throw EngineWriteFailedException((created as RecordStore.WriteResult.Failure).reason)
                }

                // ASSERTED supersession, in the same transaction as the OBSERVED create.
                val assertedGuid = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
                val asserted = db.engineRecordDao().getByGuid(assertedGuid)
                if (asserted != null && asserted.deletedAt == null) {
                    val aMileage = FleetRecordBridge.serviceHistoryMileage(asserted, schema.serviceHistory.fieldIds)
                    val aDate = FleetRecordBridge.serviceHistoryDate(asserted, schema.serviceHistory.fieldIds)
                    if (FleetRecordBridge.explainedBy(aMileage, aDate, mileage, date)) {
                        val deleteResult = recordStore.delete(asserted.id, now = date)
                        // Nothing ever references a ServiceHistory row, so BLOCK is structurally
                        // impossible here - guarded anyway so a future reference field added onto
                        // ServiceHistory can never turn this into a silent stale-anchor leak.
                        if (deleteResult is RecordStore.DeleteResult.Blocked) {
                            throw EngineWriteFailedException(
                                "could not supersede the prior anchor: ${deleteResult.blockers.joinToString()}",
                            )
                        }
                    }
                }
                outcome = InsertObservedResult.Success(created.recordId)
            }
        } catch (e: EngineWriteFailedException) {
            outcome = InsertObservedResult.Failure(e.reason)
        }
        return outcome
    }

    suspend fun editMileageAndCost(context: Context, id: Long, mileageMiles: Int, costCents: Long?): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val existing = db.engineRecordDao().getById(id)
            ?: return 0
        if (existing.recordTypeId != schema.serviceHistory.recordTypeId || existing.deletedAt != null) return 0
        val result = recordStore.update(
            id,
            mapOf(
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to mileageMiles,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to costCents,
            ),
        )
        return if (result is RecordStore.WriteResult.Success) 1 else 0
    }

    suspend fun softDeleteServiceRecord(context: Context, id: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val recordStore = store(db)
        return when (recordStore.delete(id)) {
            RecordStore.DeleteResult.Trashed -> 1
            else -> 0
        }
    }

    // =============================================================================================
    // MaintenanceSchedule (MaintenanceItem value object, anchor DERIVED from ServiceHistory)
    // =============================================================================================

    private suspend fun scheduleRecordsForVehicle(db: CarDatabase, schema: FleetAspectSeeder.Schema, vehicleEngineId: Long, includeDeleted: Boolean): List<EngineRecord> {
        val vehicleFieldId = schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE)
        val active = db.engineRecordDao().activeByRecordType(schema.maintenanceSchedule.recordTypeId)
            .filter { FleetRecordBridge.referenceId(it, vehicleFieldId) == vehicleEngineId }
        if (!includeDeleted) return active
        val trashed = db.engineRecordDao().trashedByRecordType(schema.maintenanceSchedule.recordTypeId)
            .filter { FleetRecordBridge.referenceId(it, vehicleFieldId) == vehicleEngineId }
        return active + trashed
    }

    private suspend fun toItems(db: CarDatabase, schema: FleetAspectSeeder.Schema, mac: String, vehicleEngineId: Long, schedules: List<EngineRecord>): List<MaintenanceItem> {
        val history = activeServiceHistoryForVehicle(db, schema, vehicleEngineId)
        val byService = history.groupBy { FleetRecordBridge.serviceHistoryServiceName(it, schema.serviceHistory.fieldIds) }
        return schedules.map { schedule ->
            val name = PayloadCodec.readString(
                JSONObject(schedule.payload), schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_SERVICE_NAME),
            ).orEmpty()
            FleetRecordBridge.toMaintenanceItem(
                schedule = schedule,
                scheduleFieldIds = schema.maintenanceSchedule.fieldIds,
                vehicleId = mac,
                historyForThisService = byService[name].orEmpty(),
                shFieldIds = schema.serviceHistory.fieldIds,
            )
        }
    }

    suspend fun getForVehicle(context: Context, mac: String): List<MaintenanceItem> {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleEngineId = engineVehicleId(db, mac) ?: return emptyList()
        val schedules = scheduleRecordsForVehicle(db, schema, vehicleEngineId, includeDeleted = false)
        return toItems(db, schema, mac, vehicleEngineId, schedules)
    }

    suspend fun getForVehicleIncludingDeleted(context: Context, mac: String): List<MaintenanceItem> {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val vehicleEngineId = engineVehicleId(db, mac) ?: return emptyList()
        val schedules = scheduleRecordsForVehicle(db, schema, vehicleEngineId, includeDeleted = true)
        return toItems(db, schema, mac, vehicleEngineId, schedules)
    }

    suspend fun get(context: Context, mac: String, serviceName: String): MaintenanceItem? =
        getForVehicle(context, mac).firstOrNull { it.serviceName == serviceName }

    /** Genuine create only (a fresh schedule item, hand-added or the "no match" branch of a service
     * log) - never call this to edit an existing row. Deterministic guid ([FleetRecordBridge.scheduleGuid])
     * so a retry recognizes what it already wrote rather than duplicating. */
    suspend fun upsertNewItem(context: Context, item: MaintenanceItem) {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val vehicleEngineId = engineVehicleId(db, item.vehicleId) ?: return
        val guid = FleetRecordBridge.scheduleGuid(item.vehicleId, item.serviceName)
        val existing = db.engineRecordDao().getByGuid(guid)
        if (existing != null) {
            // restore BEFORE update - RecordStore.update refuses to touch a trashed record.
            if (existing.deletedAt != null) recordStore.restore(existing.id)
            recordStore.update(existing.id, FleetRecordBridge.scheduleFieldValues(item, schema.maintenanceSchedule.fieldIds, vehicleEngineId))
        } else {
            recordStore.create(
                recordTypeId = schema.maintenanceSchedule.recordTypeId,
                fieldValues = FleetRecordBridge.scheduleFieldValues(item, schema.maintenanceSchedule.fieldIds, vehicleEngineId),
                provenance = RecordProvenance.USER,
                guid = guid,
            )
        }
        if (item.lastDoneMileage != null || item.lastDoneDate != null) {
            writeAssertedAnchor(db, schema, recordStore, vehicleEngineId, item.vehicleId, item.serviceName, item.lastDoneMileage, item.lastDoneDate, System.currentTimeMillis())
        }
    }

    /** `-1L` on a collision with an existing `(vehicleId, serviceName)` pair, active OR trashed -
     * matches [com.kevin.legion.data.local.MaintenanceItemDao.insertIgnore]'s exact contract (a
     * tombstoned row still occupies the natural key, and must be un-tombstoned via [restore], never
     * silently overwritten by an insert). Returns the new engine record id on a genuine insert. */
    suspend fun insertIgnore(context: Context, item: MaintenanceItem): Long {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val vehicleEngineId = engineVehicleId(db, item.vehicleId) ?: return -1L
        val guid = FleetRecordBridge.scheduleGuid(item.vehicleId, item.serviceName)
        if (db.engineRecordDao().getByGuid(guid) != null) return -1L
        val result = recordStore.create(
            recordTypeId = schema.maintenanceSchedule.recordTypeId,
            fieldValues = FleetRecordBridge.scheduleFieldValues(item, schema.maintenanceSchedule.fieldIds, vehicleEngineId),
            provenance = RecordProvenance.USER,
            guid = guid,
        )
        return when (result) {
            is RecordStore.WriteResult.Success -> result.recordId
            is RecordStore.WriteResult.Failure -> -1L
        }
    }

    suspend fun setIntervals(context: Context, mac: String, serviceName: String, miles: Int?, months: Int?, source: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val guid = FleetRecordBridge.scheduleGuid(mac, serviceName)
        val existing = db.engineRecordDao().getByGuid(guid) ?: return 0
        if (existing.deletedAt != null) return 0
        val result = recordStore.update(
            existing.id,
            mapOf(
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES) to miles,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS) to months,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE) to source,
            ),
            now,
        )
        return if (result is RecordStore.WriteResult.Success) 1 else 0
    }

    private suspend fun writeAssertedAnchor(
        db: CarDatabase,
        schema: FleetAspectSeeder.Schema,
        recordStore: RecordStore,
        vehicleEngineId: Long,
        mac: String,
        serviceName: String,
        mileage: Int?,
        date: Long?,
        now: Long,
    ) {
        val guid = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
        val existing = db.engineRecordDao().getByGuid(guid)
        if (mileage == null && date == null) {
            // "I don't know" clears any existing ASSERTED row to unknown - trash it rather than
            // writing a meaningless null/null row (there is nothing left for such a row to assert).
            if (existing != null && existing.deletedAt == null) recordStore.delete(existing.id, now)
            return
        }
        val fieldValues = FleetRecordBridge.assertedFieldValues(vehicleEngineId, serviceName, mileage, date, schema.serviceHistory.fieldIds)
        if (existing != null) {
            // restore BEFORE update - RecordStore.update refuses to touch a trashed record.
            if (existing.deletedAt != null) recordStore.restore(existing.id, now)
            recordStore.update(existing.id, fieldValues, now)
        } else {
            recordStore.create(
                recordTypeId = schema.serviceHistory.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = date ?: now,
                guid = guid,
            )
        }
    }

    /**
     * The engine-backed equivalent of [com.kevin.legion.data.local.MaintenanceItemDao.setAnchor] -
     * "when was this last done", never the interval. Writes/clears the deterministic `ASSERTED`
     * `ServiceHistory` row for `(mac, serviceName)` (see [FleetRecordBridge.assertedAnchorGuid]) and
     * clears `MaintenanceSchedule.neverDone` back to false (supplying a real anchor is the driver
     * un-confirming a prior "never done" - same rule the legacy `setAnchor` query enforced in one
     * UPDATE). Returns 0 (no-op, per ticket 05's law) when no `MaintenanceSchedule` row exists for
     * this pair.
     */
    suspend fun setAnchor(context: Context, mac: String, serviceName: String, mileage: Int?, date: Long?, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val scheduleGuid = FleetRecordBridge.scheduleGuid(mac, serviceName)
        val schedule = db.engineRecordDao().getByGuid(scheduleGuid) ?: return 0
        if (schedule.deletedAt != null) return 0
        val vehicleEngineId = engineVehicleId(db, mac) ?: return 0

        db.withTransaction {
            recordStore.update(schedule.id, mapOf(schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE) to false), now)
            writeAssertedAnchor(db, schema, recordStore, vehicleEngineId, mac, serviceName, mileage, date, now)
        }
        return 1
    }

    /**
     * Clears `MaintenanceSchedule.neverDone` back to false ONLY - never touches the anchor.
     * [insertObserved] already IS the new anchor the instant it lands (the projected
     * mileage/date - [FleetRecordBridge.projectAnchor] - reads straight off the `OBSERVED` row just
     * written), so [VehicleController.logServiceDirect]'s matched-item branch calls this rather than
     * [setAnchor] (which would also write/replace an `ASSERTED` row this call has no anchor value
     * for). Returns 0 (no-op, ticket 05's law) when no `MaintenanceSchedule` row exists for this pair.
     */
    suspend fun setNeverDoneCleared(context: Context, mac: String, serviceName: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val guid = FleetRecordBridge.scheduleGuid(mac, serviceName)
        val existing = db.engineRecordDao().getByGuid(guid) ?: return 0
        if (existing.deletedAt != null) return 0
        val result = recordStore.update(existing.id, mapOf(schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE) to false), now)
        return if (result is RecordStore.WriteResult.Success) 1 else 0
    }

    /** Marks `neverDone` and clears the anchor - "never done" REPLACES any prior guess, mirroring
     * [com.kevin.legion.data.local.MaintenanceItemDao.setNeverDone]'s exact contract. */
    suspend fun setNeverDone(context: Context, mac: String, serviceName: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val scheduleGuid = FleetRecordBridge.scheduleGuid(mac, serviceName)
        val schedule = db.engineRecordDao().getByGuid(scheduleGuid) ?: return 0
        if (schedule.deletedAt != null) return 0

        db.withTransaction {
            recordStore.update(schedule.id, mapOf(schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE) to true), now)
            val assertedGuid = FleetRecordBridge.assertedAnchorGuid(mac, serviceName)
            val asserted = db.engineRecordDao().getByGuid(assertedGuid)
            if (asserted != null && asserted.deletedAt == null) recordStore.delete(asserted.id, now)
        }
        return 1
    }

    /** Trashes the `MaintenanceSchedule` record only - `ServiceHistory` (both `OBSERVED` and any
     * `ASSERTED` row) survives untouched, matching [com.kevin.legion.data.local.MaintenanceItemDao.softDelete]'s
     * own doc: "deleting a schedule row does not un-do work that was actually logged". */
    suspend fun softDeleteItem(context: Context, mac: String, serviceName: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val recordStore = store(db)
        val guid = FleetRecordBridge.scheduleGuid(mac, serviceName)
        val existing = db.engineRecordDao().getByGuid(guid) ?: return 0
        return when (recordStore.delete(existing.id, now)) {
            RecordStore.DeleteResult.Trashed -> 1
            else -> 0
        }
    }

    /** Un-tombstones a row AND sets its interval in one call - the populate diff's "you deleted this
     * - add it back?" case. `-1`/0-style no-op contract per [com.kevin.legion.data.local.MaintenanceItemDao.restore]'s
     * own doc: a restore against a pair that was never tombstoned (or never existed) touches nothing. */
    suspend fun restore(context: Context, mac: String, serviceName: String, miles: Int?, months: Int?, source: String, now: Long): Int {
        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)
        val guid = FleetRecordBridge.scheduleGuid(mac, serviceName)
        val existing = db.engineRecordDao().getByGuid(guid) ?: return 0
        if (existing.deletedAt == null) return 0 // was never tombstoned - restore is a no-op by contract
        db.withTransaction {
            recordStore.restore(existing.id, now)
            recordStore.update(
                existing.id,
                mapOf(
                    schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES) to miles,
                    schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS) to months,
                    schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE) to source,
                ),
                now,
            )
        }
        return 1
    }
}
