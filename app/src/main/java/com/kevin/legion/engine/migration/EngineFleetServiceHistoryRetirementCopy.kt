package com.kevin.legion.engine.migration

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.fleet.FleetAspectSeeder
import com.kevin.legion.engine.fleet.FleetRecordBridge
import org.json.JSONObject

/**
 * Step 3 of the engine retirement sequence (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`),
 * ruling ticket 16's option 1: the one-time, idempotent copier that reconciles the engine's
 * `ServiceHistory`/`MaintenanceSchedule` records onto the legacy `service_records`/`maintenance_items`
 * tables BEFORE [com.kevin.legion.vehicle.FleetEngineStore]'s ServiceHistory/MaintenanceSchedule
 * read/write paths repoint off [com.kevin.legion.engine.RecordStore] and onto
 * [com.kevin.legion.data.local.ServiceRecordDao]/[com.kevin.legion.data.local.MaintenanceItemDao].
 * Mirrors [EnginePantryRetirementCopy]'s shape - see that object's own class doc for the general
 * reasoning ("runs the opposite direction from the wave copiers", "deletes nothing") - repeated
 * here only where fleet's answer differs.
 *
 * **Fleet is a genuine fork from places/pantry/notes, not a copy of their shape (ticket 16's own
 * finding).** Vehicle needed no repoint at all (ticket 14: co-owned, legacy-primary since cutover
 * 4). ServiceHistory/MaintenanceSchedule had NO legacy writer at all since cutover 4 - unlike
 * places/pantry, where the configured path kept a legacy table alive the whole time, fleet's
 * `service_records`/`maintenance_items` have been genuinely stale since 2026-08-24, which is why
 * [com.kevin.legion.vehicle.MonthlyRecapController.generate]'s own comment names an under-count
 * this copier (plus the repoint that follows it) closes.
 *
 * **Two different natural keys, one per record type - traced, not assumed, from
 * [EngineDataMigrationWave4], the one other writer either guid has ever had:**
 *
 * - **`ServiceHistory` (`kind = OBSERVED`)**: `EngineDataMigrationWave4.copyServiceHistoryForVehicle`
 *   keyed the FORWARD copy on `record.syncId` verbatim (`guid = guid` where `val guid = record.syncId`)
 *   - identical to pantry's answer, for the identical reason: the legacy `ServiceRecord.syncId` IS
 *   the row's own portable identity, not a semantic key. So an engine OBSERVED record's guid is
 *   always, in both directions, the same string its legacy row would carry as `syncId`.
 * - **`ServiceHistory` (`kind = ASSERTED`)**: deterministic,
 *   [FleetRecordBridge.assertedAnchorGuid]`(vehicleId, serviceName)` - there is no legacy row this
 *   kind was ever copied FROM (the carve invented this kind; no pre-cutover-4 `ServiceRecord` was
 *   ever `ASSERTED`), so this copier is the FIRST thing to ever write one into `service_records`,
 *   and it writes that same deterministic string as the row's own `syncId` so a live
 *   `FleetEngineStore.setAnchor`/`.setNeverDone` call after the repoint finds the identical row a
 *   migrated-then-copied anchor already occupies, rather than duplicating it.
 * - **`MaintenanceSchedule`**: the legacy [MaintenanceItem] table's PRIMARY KEY already IS the
 *   natural key (`(vehicleId, serviceName)`, composite) - no guid decoding needed at all, and
 *   [com.kevin.legion.data.local.MaintenanceItemDao.insertAll]'s existing `@Insert(IGNORE)` is
 *   already exactly "gap-fill only, never overwrite" (ticket 15's rule) by construction, the same
 *   protection [com.kevin.legion.data.local.MaintenanceItem.deleted]'s own doc comment already
 *   relies on for the tombstone-blocks-a-reseed guarantee.
 *
 * **`MaintenanceSchedule` rows land with `lastDoneMileage`/`lastDoneDate` left NULL, deliberately -
 * "derived, not stored" (ticket 16's ruled option 1).** Cutover 4's whole point was collapsing that
 * figure into ONE place (`ServiceHistory`) so it could never drift from a second, independently
 * writable copy. Copying the engine's already-correct anchor VALUE into this table's own dead
 * columns would silently resurrect exactly the two-independently-writable-stores shape ticket 29
 * fixed - the legacy columns exist (kept, per this map's own "removing a column buys nothing here"
 * posture elsewhere), but nothing writes them from this branch forward. Every read instead derives
 * the anchor fresh from `service_records` via [FleetRecordBridge.projectAnchorLegacy], the exact
 * shape [FleetRecordBridge.toMaintenanceItem]/[projectAnchor] already used against the engine.
 *
 * **Vehicle mapping is the SAME one-way-hash problem [com.kevin.legion.vehicle.FleetEngineStore]'s
 * own class doc already states for Vehicle reads**: an engine `Vehicle` record's guid cannot be
 * inverted back to its `obdMac`. This copier walks every legacy `Vehicle` row instead (the only
 * place every `obdMac` can be enumerated at all) and re-derives each one's guid FORWARD via
 * [FleetRecordBridge.vehicleGuid] to find its engine record id - a car with no engine `Vehicle`
 * record (should not happen post-cutover-4, but the guard costs nothing) is simply skipped, same
 * "never crash on a hole a prior wave should have filled" posture [EngineDataMigrationWave4]
 * itself takes for a vehicle whose create failed.
 *
 * **Deletes nothing, from either table, in either direction.** The engine's ServiceHistory/
 * MaintenanceSchedule records are read here and never trashed, updated, or touched - ticket 15 is
 * explicit that nothing is deleted until every aspect is repointed and soaked.
 */
object EngineFleetServiceHistoryRetirementCopy {
    private const val PREFS = "engine_fleet_service_history_retirement"
    private const val KEY_COMPLETED = "fleet_service_history_repointed_v1"

    /** Counts only rows actually written this call. [alreadyDone] is true only when the
     * SharedPreferences fast path skipped the whole pass without even reading the engine. */
    data class Result(val serviceHistoryCopied: Int, val maintenanceScheduleCopied: Int, val alreadyDone: Boolean)

    suspend fun copyIfNeeded(context: Context): Result {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_COMPLETED, false)) {
            return Result(serviceHistoryCopied = 0, maintenanceScheduleCopied = 0, alreadyDone = true)
        }

        val db = CarDatabase.getDatabase(context)
        val schema = FleetAspectSeeder.ensureSeeded(context)

        // engine Vehicle record id -> legacy obdMac, built the only way it can be (see class doc).
        val macByVehicleEngineId = mutableMapOf<Long, String>()
        for (vehicle in db.vehicleDao().getAllIncludingArchived()) {
            val engineVehicle = db.engineRecordDao().getByGuid(FleetRecordBridge.vehicleGuid(vehicle.obdMac)) ?: continue
            macByVehicleEngineId[engineVehicle.id] = vehicle.obdMac
        }

        val existingSyncIds = mutableSetOf<String>() // guards two engine records that somehow share a guid within one pass
        var serviceHistoryCopied = 0

        for (record in db.engineRecordDao().activeByRecordType(schema.serviceHistory.recordTypeId)) {
            if (record.guid in existingSyncIds) continue
            if (db.serviceRecordDao().getBySyncId(record.guid) != null) {
                existingSyncIds += record.guid
                continue // `service_records` wins ties - reconcile, never overwrite (ticket 15's rule)
            }
            val vehicleEngineId = FleetRecordBridge.serviceHistoryVehicleId(record, schema.serviceHistory.fieldIds) ?: continue
            val mac = macByVehicleEngineId[vehicleEngineId] ?: continue

            db.serviceRecordDao().insert(
                ServiceRecord(
                    vehicleId = mac,
                    serviceName = FleetRecordBridge.serviceHistoryServiceName(record, schema.serviceHistory.fieldIds),
                    mileage = FleetRecordBridge.serviceHistoryMileage(record, schema.serviceHistory.fieldIds),
                    date = FleetRecordBridge.serviceHistoryDate(record, schema.serviceHistory.fieldIds),
                    costCents = PayloadCodec.readLong(
                        JSONObject(record.payload), schema.serviceHistory.fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST),
                    ),
                    syncId = record.guid,
                    deleted = false,
                    kind = FleetRecordBridge.kindOf(record, schema.serviceHistory.fieldIds),
                    updatedAt = record.updatedAt,
                ),
            )
            existingSyncIds += record.guid
            serviceHistoryCopied++
        }

        // MaintenanceSchedule - natural-key gap fill via insertAll's own IGNORE, see class doc.
        val candidates = mutableListOf<MaintenanceItem>()
        for (record in db.engineRecordDao().activeByRecordType(schema.maintenanceSchedule.recordTypeId)) {
            val vehicleFieldId = schema.maintenanceSchedule.fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE)
            val vehicleEngineId = FleetRecordBridge.referenceId(record, vehicleFieldId) ?: continue
            val mac = macByVehicleEngineId[vehicleEngineId] ?: continue
            val payload = JSONObject(record.payload)
            fun readS(name: String) = PayloadCodec.readString(payload, schema.maintenanceSchedule.fieldIds.getValue(name))
            fun readL(name: String) = PayloadCodec.readLong(payload, schema.maintenanceSchedule.fieldIds.getValue(name))
            fun readB(name: String) = PayloadCodec.readBoolean(payload, schema.maintenanceSchedule.fieldIds.getValue(name))
            candidates += MaintenanceItem(
                vehicleId = mac,
                serviceName = readS(FleetAspectSeeder.FIELD_MS_SERVICE_NAME).orEmpty(),
                intervalMiles = readL(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES)?.toInt(),
                intervalMonths = readL(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS)?.toInt(),
                // Deliberately null - "derived, not stored" - see this object's own class doc.
                lastDoneMileage = null,
                lastDoneDate = null,
                updatedAt = record.updatedAt,
                neverDone = readB(FleetAspectSeeder.FIELD_MS_NEVER_DONE),
                intervalSource = readS(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE) ?: "SEEDED",
                deleted = false,
            )
        }
        db.maintenanceItemDao().insertAll(candidates)
        // insertAll's IGNORE makes an exact "copied" count unobservable from the call's own return
        // (Unit), so it is approximated as "candidates considered" for reporting purposes only -
        // the correctness guarantee (gap-fill only, never overwrite) comes from IGNORE itself, not
        // from this count. Real per-row idempotence is exercised directly in the copier's tests.
        val maintenanceScheduleCopied = candidates.size

        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
        return Result(serviceHistoryCopied = serviceHistoryCopied, maintenanceScheduleCopied = maintenanceScheduleCopied, alreadyDone = false)
    }
}
