package com.kevin.legion.engine.fleet

import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.PayloadCodec
import org.json.JSONObject
import java.util.UUID

/**
 * Cutover 4 (`docs/architecture/cutover4-2026-08-24.md`). The ONE place `Vehicle`/`ServiceRecord`/
 * `MaintenanceItem` <-> [EngineRecord] translation happens for the Fleet aspect, mirroring
 * [com.kevin.legion.engine.ledger.LedgerRecordBridge]'s own reasoning: before this file existed,
 * [com.kevin.legion.engine.migration.EngineDataMigrationWave4] held the field map and the
 * deterministic-guid derivation inline, as the only writer at the time. Cutover adds real live
 * writers/readers (`vehicle/FleetEngineStore.kt`) that must use the EXACT SAME mapping and the EXACT
 * SAME guid derivation the migration already used - reusing this file rather than duplicating either
 * is what keeps a post-cutover `setAnchor` call landing on the SAME `ASSERTED` row a pre-cutover
 * migration might already have written for that `(vehicleId, serviceName)` pair.
 *
 * **The headline unification (wave4-carve's own finding, ticket 29):** `ServiceHistory` carries BOTH
 * a real logged service (`kind = OBSERVED`) and a user-stated anchor with no backing event
 * (`kind = ASSERTED`) in ONE record type/table, so [projectAnchor] - the function that reconstructs a
 * [MaintenanceItem]'s `lastDoneMileage`/`lastDoneDate` for every live caller - has exactly one place
 * to read from. There is no second store of "when was this last done" left to disagree with it.
 */
object FleetRecordBridge {

    // ---- deterministic identity derivation, reused verbatim from EngineDataMigrationWave4 -------
    // Same three derivations that object already used privately - moved here so the LIVE write path
    // (FleetEngineStore) and the MIGRATION/catch-up path never drift into two independently-typed
    // implementations of "how do I address this row". A post-cutover `setAnchor` call and a
    // pre-cutover migrated ASSERTED row for the identical (vehicleId, serviceName) MUST resolve to
    // the same guid, or cutover would create a duplicate ASSERTED row instead of updating the one
    // migration already wrote.

    fun vehicleGuid(obdMac: String): String =
        UUID.nameUUIDFromBytes("fleet-vehicle:$obdMac".toByteArray()).toString()

    fun scheduleGuid(vehicleId: String, serviceName: String): String =
        UUID.nameUUIDFromBytes("fleet-schedule:$vehicleId:$serviceName".toByteArray()).toString()

    fun assertedAnchorGuid(vehicleId: String, serviceName: String): String =
        UUID.nameUUIDFromBytes("fleet-anchor:$vehicleId:$serviceName".toByteArray()).toString()

    // ---- Vehicle -----------------------------------------------------------------------------
    // Only the nine carried fields (wave4-carve's own field-mapping table) - obdMac/persona*/
    // archived/onboarded/lastOdometerPromptAt/tripMilesSinceBaseline are deliberately absent; those
    // live exclusively on the legacy mirror row FleetEngineStore keeps in sync (see that file's own
    // class doc for why obdMac in particular cannot be recovered from a one-way guid hash, and so
    // the mirror - not the engine payload - is what a Vehicle READ is actually built from).

    fun vehicleFieldValues(v: Vehicle, fieldIds: Map<String, Long>): Map<Long, Any?> = mapOf(
        fieldIds.getValue(FleetAspectSeeder.FIELD_NAME) to v.name,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MAKE) to v.make,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MODEL) to v.model,
        fieldIds.getValue(FleetAspectSeeder.FIELD_YEAR) to v.year,
        fieldIds.getValue(FleetAspectSeeder.FIELD_TRIM) to v.trim,
        fieldIds.getValue(FleetAspectSeeder.FIELD_ENGINE) to v.engine,
        fieldIds.getValue(FleetAspectSeeder.FIELD_CONFIRMED) to v.confirmed,
        fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE) to v.odometerBaseline,
        fieldIds.getValue(FleetAspectSeeder.FIELD_ODOMETER_BASELINE_AT) to v.odometerBaselineAt,
    )

    // ---- ServiceHistory ------------------------------------------------------------------------

    /** [record]'s field map for a real, logged event - always `kind = OBSERVED`. [ASSERTED] rows are
     * built by [FleetEngineStore] directly (they have no backing [ServiceRecord] to convert from). */
    fun observedFieldValues(record: ServiceRecord, fieldIds: Map<String, Long>, vehicleEngineId: Long): Map<Long, Any?> = mapOf(
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) to vehicleEngineId,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME) to record.serviceName,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to record.mileage,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE) to record.date,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to record.costCents,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) to FleetAspectSeeder.KIND_OBSERVED,
    )

    fun assertedFieldValues(
        vehicleEngineId: Long,
        serviceName: String,
        mileage: Int?,
        date: Long?,
        fieldIds: Map<String, Long>,
    ): Map<Long, Any?> = mapOf(
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE) to vehicleEngineId,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME) to serviceName,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE) to mileage,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE) to date,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST) to null,
        fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND) to FleetAspectSeeder.KIND_ASSERTED,
    )

    /** `engineRecord.id` is repurposed as [ServiceRecord.id] - the SAME "opaque per-row handle" role
     * the legacy Room autoincrement id already played (nothing in this codebase does arithmetic on
     * it; every caller round-trips it straight into `getById`/`editMileageAndCost`/`softDelete`), so
     * a caller that got this id from a read can hand it straight back to a write with no translation
     * layer of its own. Only ever called for `kind = OBSERVED` rows - callers filter by kind first
     * (an `ASSERTED` row has no meaningful [ServiceRecord.mileage]/`date` NON-null guarantee the way
     * a real event does, and no caller of this conversion ever wants one). */
    fun toServiceRecord(record: EngineRecord, fieldIds: Map<String, Long>): ServiceRecord {
        val payload = JSONObject(record.payload)
        return ServiceRecord(
            id = record.id,
            vehicleId = "", // filled by the caller, which already knows which vehicle it queried for
            serviceName = PayloadCodec.readString(payload, fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME)).orEmpty(),
            mileage = PayloadCodec.readLong(payload, fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE))?.toInt() ?: 0,
            date = PayloadCodec.readLong(payload, fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE)) ?: record.createdAt,
            costCents = PayloadCodec.readLong(payload, fieldIds.getValue(FleetAspectSeeder.FIELD_SH_COST)),
            syncId = record.guid,
            deleted = false, // engine reads are already deletedAt == null filtered by the DAO query
        )
    }

    fun kindOf(record: EngineRecord, fieldIds: Map<String, Long>): String =
        PayloadCodec.readString(JSONObject(record.payload), fieldIds.getValue(FleetAspectSeeder.FIELD_SH_KIND))
            ?: FleetAspectSeeder.KIND_OBSERVED

    fun serviceHistoryMileage(record: EngineRecord, fieldIds: Map<String, Long>): Int? =
        PayloadCodec.readLong(JSONObject(record.payload), fieldIds.getValue(FleetAspectSeeder.FIELD_SH_MILEAGE))?.toInt()

    fun serviceHistoryDate(record: EngineRecord, fieldIds: Map<String, Long>): Long? =
        PayloadCodec.readLong(JSONObject(record.payload), fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_DATE))

    fun serviceHistoryServiceName(record: EngineRecord, fieldIds: Map<String, Long>): String =
        PayloadCodec.readString(JSONObject(record.payload), fieldIds.getValue(FleetAspectSeeder.FIELD_SH_SERVICE_NAME)).orEmpty()

    fun serviceHistoryVehicleId(record: EngineRecord, fieldIds: Map<String, Long>): Long? =
        PayloadCodec.readLong(JSONObject(record.payload), fieldIds.getValue(FleetAspectSeeder.FIELD_SH_VEHICLE))

    /** Same read, generic over any [FieldType.REFERENCE] field id - used for `MaintenanceSchedule.vehicle`,
     * whose fieldIds map keys the identical string (`FleetAspectSeeder.FIELD_MS_VEHICLE == "vehicle"`)
     * onto a DIFFERENT field-def id than `ServiceHistory.vehicle` does. */
    fun referenceId(record: EngineRecord, fieldId: Long): Long? =
        PayloadCodec.readLong(JSONObject(record.payload), fieldId)

    // ---- MaintenanceSchedule --------------------------------------------------------------------

    fun scheduleFieldValues(item: MaintenanceItem, fieldIds: Map<String, Long>, vehicleEngineId: Long): Map<Long, Any?> = mapOf(
        fieldIds.getValue(FleetAspectSeeder.FIELD_MS_VEHICLE) to vehicleEngineId,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MS_SERVICE_NAME) to item.serviceName,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES) to item.intervalMiles,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS) to item.intervalMonths,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE) to item.intervalSource,
        fieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE) to item.neverDone,
    )

    /**
     * Reconstructs a [MaintenanceItem] value object from a `MaintenanceSchedule` engine record plus
     * every `ServiceHistory` row (`OBSERVED` and `ASSERTED` alike) already known for that same
     * `(vehicleId, serviceName)` pair - [projectAnchor] does the actual derivation. This is the
     * function that closes ticket 29: every caller that used to read `MaintenanceItem.lastDoneMileage`/
     * `lastDoneDate` straight off a second, independently-writable column now reads a value derived
     * fresh, every time, from the SAME `ServiceHistory` table the service-history screen and every
     * voice answer also read - there is no longer a second place either figure could have been left
     * stale.
     */
    fun toMaintenanceItem(
        schedule: EngineRecord,
        scheduleFieldIds: Map<String, Long>,
        vehicleId: String,
        historyForThisService: List<EngineRecord>,
        shFieldIds: Map<String, Long>,
    ): MaintenanceItem {
        val payload = JSONObject(schedule.payload)
        val (mileage, date) = projectAnchor(historyForThisService, shFieldIds)
        return MaintenanceItem(
            vehicleId = vehicleId,
            serviceName = PayloadCodec.readString(payload, scheduleFieldIds.getValue(FleetAspectSeeder.FIELD_MS_SERVICE_NAME)).orEmpty(),
            intervalMiles = PayloadCodec.readLong(payload, scheduleFieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MILES))?.toInt(),
            intervalMonths = PayloadCodec.readLong(payload, scheduleFieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_MONTHS))?.toInt(),
            lastDoneMileage = mileage,
            lastDoneDate = date,
            updatedAt = schedule.updatedAt,
            neverDone = PayloadCodec.readBoolean(payload, scheduleFieldIds.getValue(FleetAspectSeeder.FIELD_MS_NEVER_DONE)),
            intervalSource = PayloadCodec.readString(payload, scheduleFieldIds.getValue(FleetAspectSeeder.FIELD_MS_INTERVAL_SOURCE)) ?: "SEEDED",
            deleted = false, // engine reads are already deletedAt == null filtered by the DAO query
        )
    }

    /**
     * "Records are the events. The clock is a projection of them, plus an explicit override" -
     * ticket 29's own framing, applied per axis rather than per row: the mileage anchor is the
     * HIGHEST mileage stated by any `ServiceHistory` row (`OBSERVED` or `ASSERTED`) for this
     * service - an odometer only ever goes up, so the highest stated mileage is the most recent real
     * knowledge regardless of which row happens to carry it - and the date anchor is independently
     * the LATEST date stated by any row. The two axes are allowed to come from DIFFERENT rows,
     * exactly mirroring the legacy schema's own two independently-settable columns
     * (`MaintenanceItemDao.setAnchor` always wrote both together, but nothing ever required them to
     * have originated from the same write). A vehicle with no history for this service on either
     * axis reports `null, null` - genuinely unknown, not zero.
     */
    fun projectAnchor(historyForThisService: List<EngineRecord>, shFieldIds: Map<String, Long>): Pair<Int?, Long?> {
        val mileage = historyForThisService.mapNotNull { serviceHistoryMileage(it, shFieldIds) }.maxOrNull()
        val date = historyForThisService.mapNotNull { serviceHistoryDate(it, shFieldIds) }.maxOrNull()
        return mileage to date
    }

    /**
     * The migration's own dedup rule (`docs/architecture/wave4-carve-2026-08-23.md`'s "ServiceHistory's
     * dedup rule" section, senior-review-corrected 2026-08-24), reused VERBATIM for the live
     * ASSERTED-supersession this cutover adds (instruction 3): an anchor is "explained" by a
     * candidate `OBSERVED` row only when EVERY axis the anchor actually states agrees with that SAME
     * row - a null anchor axis is vacuously satisfied, a non-null axis must match exactly. Never ORs
     * across two different candidate rows (the exact MUST-FIX the carve doc's own history records).
     */
    fun explainedBy(anchorMileage: Int?, anchorDate: Long?, candidateMileage: Int?, candidateDate: Long?): Boolean =
        (anchorMileage == null || candidateMileage == anchorMileage) &&
            (anchorDate == null || candidateDate == anchorDate)
}
