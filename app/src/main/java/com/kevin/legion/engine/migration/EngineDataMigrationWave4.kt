package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge

/**
 * The one-time, idempotent copier that carries Wave 4's live data (`.scratch/aspect-engine/issues/
 * 21-migration-waves.md` point 2 - "then fleet") onto the engine through [RecordStore], the
 * engine's single write door. **Additive only**: reads the legacy `vehicles`/`service_records`/
 * `maintenance_items` tables, writes new [com.kevin.legion.data.local.EngineRecord] rows; never
 * touches, drops, or mutates a legacy table. `vehicle/`, every fleet screen, and every old
 * `LiveToolbox` tool keep working unchanged - cutover is a later, per-aspect wave (ticket 14 point
 * 2), not this one. Same two-layer idempotency shape (completion flag + per-row `guid` backstop) as
 * [EngineDataMigrationWave1]/[EngineDataMigrationWave2]/[EngineDataMigrationWave3].
 *
 * **This wave migrates only the core chain** (instruction 6 of the wave 4 brief): vehicles, service
 * records, maintenance items, odometer. `BuildEntry`/`VehicleSpec`/`OilAnalysis`/`MonthlyRecap`/
 * `DailyDriveLog`/`YearlyWrapped`/`Drive` are deferred to a named follow-up wave; `OdbSample`/
 * `CodeEvent`/`CodeClearEvent`/`VehicleCapability`/`DriveReassignment`/`ChassisQuirk`/
 * `ForesightNote` stay plugin-internal permanently. `CarTask` was already ruled dead by Wave 1. Full
 * reasoning: `docs/architecture/wave4-carve-2026-08-23.md`.
 *
 * **The carve's headline move**: [com.kevin.legion.data.local.ServiceRecord] and
 * [com.kevin.legion.data.local.MaintenanceItem]'s `lastDoneMileage`/`lastDoneDate` anchor are two
 * independently-writable stores of one fact (`.scratch/hands-and-senses/issues/
 * 29-one-source-for-service-history.md`'s diagnosis) - this copier unifies them into ONE
 * `ServiceHistory` record type, tagging each row `kind = OBSERVED` (a real logged service) or
 * `kind = ASSERTED` (a user-stated anchor with no backing event). [copyServiceHistoryForVehicle]'s
 * own doc comment states the exact, non-guessing dedup rule used to decide when an anchor needs its
 * own `ASSERTED` row versus being already explained by an `OBSERVED` one.
 *
 * **Copy order, strictly enforced**: [copyVehiclesIfNeeded] before ANY `ServiceHistory`/
 * `MaintenanceSchedule` row, because both reference `Vehicle` via a live
 * [com.kevin.legion.data.local.FieldType.REFERENCE] field and [RecordStore.create]'s
 * `validateReferences` step rejects any reference pointing at a record id that does not yet exist -
 * same ordering discipline Wave 2's receipts-before-line-items already established. A per-vehicle
 * `legacy obdMac -> new EngineRecord.id` map, built while copying vehicles, is threaded through the
 * whole call so a process death between vehicles and their children can never leave an orphaned
 * reference: the next run's per-row `guid` check recognizes whatever it already wrote and resumes.
 *
 * **Provenance is unconditionally [RecordProvenance.USER]** for every row this wave writes, across
 * all three record types - fleet has no document-ingestion path at all (CLAUDE.md §4's
 * reconciliation gate does not apply here, same reasoning [EngineDataMigrationWave1]'s notes/places
 * copy already states for `USER`).
 */
object EngineDataMigrationWave4 {
    private const val PREFS = "engine_migration_wave4"
    private const val KEY_FLEET_COMPLETED = "fleet_completed_v1"

    /** Counts only rows actually written THIS call - a row skipped because its `guid` already
     * existed (the per-row idempotency backstop) is not counted twice across retries.
     * [alreadyDone] is true only when the SharedPreferences fast path skipped the whole domain
     * without even querying the legacy tables. */
    data class Result(
        val vehiclesCopied: Int,
        val serviceHistoryCopied: Int,
        val maintenanceScheduleCopied: Int,
        val alreadyDone: Boolean,
    )

    private fun store(db: CarDatabase) = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    suspend fun copyFleetIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_FLEET_COMPLETED, false)) {
            return Result(vehiclesCopied = 0, serviceHistoryCopied = 0, maintenanceScheduleCopied = 0, alreadyDone = true)
        }

        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)
        val recordStore = store(db)

        var anyFailure = false

        // ---- Vehicles first, unconditionally - see this object's own class doc for why order
        // matters here. Archived cars are included (getAllIncludingArchived): archived means
        // hidden from the roster/picker, not deleted, and its service history is exactly as real.
        val vehicles = db.vehicleDao().getAllIncludingArchived()
        var vehiclesCopied = 0
        val vehicleEngineIdByMac = mutableMapOf<String, Long>()
        for (vehicle in vehicles) {
            val guid = vehicleGuid(vehicle.obdMac)
            val existing = db.engineRecordDao().getByGuid(guid)
            if (existing != null) {
                vehicleEngineIdByMac[vehicle.obdMac] = existing.id
                continue // already copied by an earlier, interrupted pass
            }

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_NAME) to vehicle.name,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE) to vehicle.make,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL) to vehicle.model,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR) to vehicle.year,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM) to vehicle.trim,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ENGINE) to vehicle.engine,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED) to vehicle.confirmed,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE) to vehicle.odometerBaseline,
                schema.vehicle.fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT) to vehicle.odometerBaselineAt,
            )
            // Vehicle carries no creation timestamp distinct from its own last-edit clock -
            // updatedAt is the closest available anchor for both, same substitution shape Wave
            // 2/3's carves already name as deliberate rather than implicit.
            val result = recordStore.create(
                recordTypeId = schema.vehicle.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = vehicle.updatedAt,
                guid = guid,
            )
            when (result) {
                is RecordStore.WriteResult.Success -> {
                    vehiclesCopied++
                    vehicleEngineIdByMac[vehicle.obdMac] = result.recordId
                }
                is RecordStore.WriteResult.Failure -> anyFailure = true
            }
        }

        // ---- ServiceHistory + MaintenanceSchedule, per vehicle whose engine id is known this call.
        var serviceHistoryCopied = 0
        var maintenanceScheduleCopied = 0
        for ((mac, vehicleEngineId) in vehicleEngineIdByMac) {
            val serviceRecords = db.serviceRecordDao().getRecordsForVehicleOnce(mac)
            val maintenanceItems = db.maintenanceItemDao().getForVehicle(mac)

            val shOutcome = copyServiceHistoryForVehicle(
                db = db,
                recordStore = recordStore,
                schema = schema,
                vehicleId = mac,
                vehicleEngineId = vehicleEngineId,
                serviceRecords = serviceRecords,
                maintenanceItems = maintenanceItems,
            )
            serviceHistoryCopied += shOutcome.copied
            if (shOutcome.anyFailure) anyFailure = true

            val msOutcome = copyMaintenanceScheduleForVehicle(
                db = db,
                recordStore = recordStore,
                schema = schema,
                vehicleId = mac,
                vehicleEngineId = vehicleEngineId,
                maintenanceItems = maintenanceItems,
            )
            maintenanceScheduleCopied += msOutcome.copied
            if (msOutcome.anyFailure) anyFailure = true
        }

        // Only mark the whole domain complete if nothing was left unattempted this pass - same
        // "a check that passes when nothing parsed is not a gate" posture Wave 2/3's own completion
        // folding already applies to migration completeness, not just the reconciliation gate itself.
        if (!anyFailure) prefs.edit().putBoolean(KEY_FLEET_COMPLETED, true).apply()

        return Result(
            vehiclesCopied = vehiclesCopied,
            serviceHistoryCopied = serviceHistoryCopied,
            maintenanceScheduleCopied = maintenanceScheduleCopied,
            alreadyDone = false,
        )
    }

    private data class DomainOutcome(val copied: Int, val anyFailure: Boolean)

    /**
     * Writes every `OBSERVED` [ServiceHistory][com.kevin.legion.data.local] row (one per non-
     * deleted [ServiceRecord]), THEN every `ASSERTED` row (one per [MaintenanceItem] anchor that is
     * NOT already explained by an `OBSERVED` row for the same `(vehicleId, serviceName)` pair).
     *
     * **The dedup rule, stated exactly (see the carve doc's own section for the full reasoning):**
     * an anchor (`lastDoneMileage`/`lastDoneDate`, at least one non-null) is "explained" - and gets
     * NO `ASSERTED` row - if any SINGLE `OBSERVED` row for the same vehicle+service agrees with the
     * anchor on EVERY axis the anchor actually states (`mileage == lastDoneMileage` when
     * `lastDoneMileage` is non-null, AND `date == lastDoneDate` when `lastDoneDate` is non-null - a
     * null anchor axis is vacuously satisfied, since there is nothing on it to contradict). **Both
     * present axes must match the SAME row** - senior review, 2026-08-24 (MUST-FIX): the first cut
     * of this rule OR'd the two axes across the whole `sameService` list, so a coincidental mileage
     * match on one row and an unrelated (or absent) date match on a different row could each satisfy
     * one half of "explained" independently, silently dropping the axis that never actually matched
     * anything. This is deliberately conservative: it can under-collapse (a near-miss anchor still
     * gets its own `ASSERTED` row) but it can never merge two different services' evidence or
     * silently drop a fact - see `.scratch/hands-and-senses/issues/29-one-source-for-service-history.md`'s
     * own warning: "a migration that guesses is worse than one that asks."
     */
    private suspend fun copyServiceHistoryForVehicle(
        db: CarDatabase,
        recordStore: RecordStore,
        schema: FleetAspectSeeder.Schema,
        vehicleId: String,
        vehicleEngineId: Long,
        serviceRecords: List<ServiceRecord>,
        maintenanceItems: List<MaintenanceItem>,
    ): DomainOutcome {
        var copied = 0
        var anyFailure = false

        for (record in serviceRecords) {
            val guid = record.syncId
            if (db.engineRecordDao().getByGuid(guid) != null) continue // already copied

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) to vehicleEngineId,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME) to record.serviceName,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to record.mileage,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE) to record.date,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to record.costCents,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) to FleetAspectSeeder.KIND_OBSERVED,
            )
            val result = recordStore.create(
                recordTypeId = schema.serviceHistory.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = record.date,
                guid = guid,
            )
            when (result) {
                is RecordStore.WriteResult.Success -> copied++
                is RecordStore.WriteResult.Failure -> anyFailure = true
            }
        }

        // Anchors, evaluated against the OBSERVED rows just read for this same vehicle (not a
        // fresh re-query) - the dedup rule needs the full observed set for this pair in hand.
        for (item in maintenanceItems) {
            val hasAnchor = item.lastDoneMileage != null || item.lastDoneDate != null
            if (!hasAnchor) continue

            // Senior review MUST-FIX (2026-08-24): the previous version OR'd the two axes across
            // the WHOLE `sameService` list, so a coincidental mileage match on one row and a
            // coincidental (or absent) date match on a DIFFERENT row could each satisfy one half of
            // `explained` independently - a two-field anchor was then treated as fully explained
            // even though no SINGLE observed row actually backs both facts, silently dropping the
            // date (or mileage) the driver stated with no ASSERTED row to carry it. Both non-null
            // anchor fields must now match the SAME row - a null anchor field is vacuously
            // satisfied (there is nothing on that axis to contradict), but a present one must agree
            // with that row exactly.
            val sameService = serviceRecords.filter { it.serviceName == item.serviceName }
            val explained = sameService.any { sr ->
                (item.lastDoneMileage == null || sr.mileage == item.lastDoneMileage) &&
                    (item.lastDoneDate == null || sr.date == item.lastDoneDate)
            }
            if (explained) continue

            val guid = assertedAnchorGuid(item.vehicleId, item.serviceName)
            if (db.engineRecordDao().getByGuid(guid) != null) continue // already copied

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) to vehicleEngineId,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME) to item.serviceName,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to item.lastDoneMileage,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE) to item.lastDoneDate,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to null,
                schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) to FleetAspectSeeder.KIND_ASSERTED,
            )
            // No real event timestamp exists for a bare anchor - lastDoneDate when present,
            // otherwise the schedule item's own last edit (updatedAt) is the closest anchor.
            val result = recordStore.create(
                recordTypeId = schema.serviceHistory.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = item.lastDoneDate ?: item.updatedAt,
                guid = guid,
            )
            when (result) {
                is RecordStore.WriteResult.Success -> copied++
                is RecordStore.WriteResult.Failure -> anyFailure = true
            }
        }

        return DomainOutcome(copied, anyFailure)
    }

    /** Writes one `MaintenanceSchedule` row per non-deleted [MaintenanceItem] - the interval
     * definition only, deliberately carrying no `lastDoneMileage`/`lastDoneDate` (see this object's
     * own class doc and [FleetAspectSeeder]'s doc comment for why). */
    private suspend fun copyMaintenanceScheduleForVehicle(
        db: CarDatabase,
        recordStore: RecordStore,
        schema: FleetAspectSeeder.Schema,
        vehicleId: String,
        vehicleEngineId: Long,
        maintenanceItems: List<MaintenanceItem>,
    ): DomainOutcome {
        var copied = 0
        var anyFailure = false

        for (item in maintenanceItems) {
            val guid = scheduleGuid(item.vehicleId, item.serviceName)
            if (db.engineRecordDao().getByGuid(guid) != null) continue // already copied

            val fieldValues: Map<Long, Any?> = mapOf(
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE) to vehicleEngineId,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_SERVICE_NAME) to item.serviceName,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES) to item.intervalMiles,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS) to item.intervalMonths,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE) to item.intervalSource,
                schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE) to item.neverDone,
            )
            val result = recordStore.create(
                recordTypeId = schema.maintenanceSchedule.recordTypeId,
                fieldValues = fieldValues,
                provenance = RecordProvenance.USER,
                now = item.updatedAt,
                guid = guid,
            )
            when (result) {
                is RecordStore.WriteResult.Success -> copied++
                is RecordStore.WriteResult.Failure -> anyFailure = true
            }
        }

        return DomainOutcome(copied, anyFailure)
    }

    // ---- deterministic identity derivation ----------------------------------------------------
    // Vehicle/MaintenanceItem carry no syncId of their own (Vehicle's PK is the natural obdMac key;
    // MaintenanceItem's PK is the composite (vehicleId, serviceName)) - a deterministic
    // UUID.nameUUIDFromBytes off the natural key, same shape Wave 1's TaggedPlace.label derivation
    // already established, so a second run recognizes and skips a row it already wrote rather than
    // duplicating it under a fresh random UUID.
    //
    // Cutover 4 (`docs/architecture/cutover4-2026-08-24.md`): these three derivations moved onto
    // [FleetRecordBridge] so the live write path (`vehicle/FleetEngineStore.kt`) and this migration
    // resolve the IDENTICAL guid for the identical natural key - a post-cutover `setAnchor` call
    // must land on the SAME `ASSERTED` row a pre-cutover migration already wrote for that
    // `(vehicleId, serviceName)` pair, not a duplicate one. Delegated, not duplicated.

    private fun vehicleGuid(obdMac: String): String = FleetRecordBridge.vehicleGuid(obdMac)

    private fun scheduleGuid(vehicleId: String, serviceName: String): String = FleetRecordBridge.scheduleGuid(vehicleId, serviceName)

    private fun assertedAnchorGuid(vehicleId: String, serviceName: String): String = FleetRecordBridge.assertedAnchorGuid(vehicleId, serviceName)

    /** App-start convenience, wrapped so a failure here can never cost anything else - same L12
     * "independent failure mode" reasoning [EngineDataMigrationWave1.runAll]/
     * [EngineDataMigrationWave2.runAll]/[EngineDataMigrationWave3.runAll] already use. */
    suspend fun runAll(context: Context) {
        runCatching { copyFleetIfNeeded(context) }
    }
}
