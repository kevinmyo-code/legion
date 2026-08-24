package com.kevin.legion.engine.fleet

import android.content.Context
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.FieldConfig

/**
 * The built-in Fleet aspect - Wave 4 (the last and biggest) of
 * `.scratch/aspect-engine/issues/21-migration-waves.md` ("notes/lists/places, then pantry, then
 * ledger, then fleet"). Follows [com.kevin.legion.engine.ledger.LedgerAspectSeeder]/
 * [com.kevin.legion.engine.pantry.PantryAspectSeeder]'s exact idempotent-at-every-granularity
 * shape - see those objects' own doc comments for the reasoning this one does not repeat.
 *
 * **The carve, in full, is `docs/architecture/wave4-carve-2026-08-23.md`.** Its headline finding:
 * `service_records` (events) and `maintenance_items.lastDoneMileage`/`lastDoneDate` (the schedule's
 * own clock) are two independently-writable stores of one fact - the exact drift
 * `.scratch/hands-and-senses/issues/29-one-source-for-service-history.md` diagnosed and parked here
 * ("superseded by the aspect engine... Wave 4"). This seeder lands THREE record types rather than
 * a straight one-table-per-entity carve:
 *
 * - **`Vehicle`** - the reference target for the other two (identity plus the odometer reading).
 * - **`ServiceHistory`** - the unification. Carries BOTH a real logged service (`kind = OBSERVED`,
 *   migrated off [com.kevin.legion.data.local.ServiceRecord]) and a user-stated anchor with no
 *   backing event (`kind = ASSERTED`, migrated off an unexplained
 *   [com.kevin.legion.data.local.MaintenanceItem] anchor) in the SAME table, so a schedule check or
 *   a display built against this record type after cutover reads one history, not two that can
 *   disagree.
 * - **`MaintenanceSchedule`** - the interval DEFINITION only (how often, on what axis, who set it,
 *   never-done) - deliberately carries no last-done figure of its own; that figure now lives
 *   exclusively in `ServiceHistory`, which is what deletes the drift by construction rather than
 *   reconciling it after the fact.
 *
 * [ensureSeeded] returns all three schemas so the migration copier (which must create every
 * `Vehicle` before any `ServiceHistory`/`MaintenanceSchedule` row that references it - see that
 * copier's own doc comment) has all three available from a single call.
 */
object FleetAspectSeeder {
    const val ASPECT_NAME = "Fleet"
    const val OWNER_PLUGIN_ID = "fleet"

    const val VEHICLE_RECORD_TYPE_NAME = "Vehicle"
    const val SERVICE_HISTORY_RECORD_TYPE_NAME = "ServiceHistory"
    const val MAINTENANCE_SCHEDULE_RECORD_TYPE_NAME = "MaintenanceSchedule"

    // ---- Vehicle fields ---------------------------------------------------------------------
    const val FIELD_NAME = "name"
    const val FIELD_MAKE = "make"
    const val FIELD_MODEL = "model"
    const val FIELD_YEAR = "year"
    const val FIELD_TRIM = "trim"
    const val FIELD_ENGINE = "engine"
    const val FIELD_CONFIRMED = "confirmed"
    const val FIELD_ODOMETER_BASELINE = "odometerBaseline"
    const val FIELD_ODOMETER_BASELINE_AT = "odometerBaselineAt"

    // ---- ServiceHistory fields ----------------------------------------------------------------
    const val FIELD_SH_VEHICLE = "vehicle"
    const val FIELD_SH_SERVICE_NAME = "serviceName"
    const val FIELD_SH_MILEAGE = "mileage"
    const val FIELD_SH_SERVICE_DATE = "serviceDate"
    const val FIELD_SH_COST = "costCents"
    const val FIELD_SH_KIND = "kind"

    /** `ServiceHistory.kind` options - see the carve doc's "finding that reshapes this carve"
     * section for what each means. Never [com.kevin.legion.data.local.RecordProvenance] (that
     * column is unconditionally `USER` for this whole wave - see the migration copier's own doc
     * comment) - this is a SEPARATE distinction, "was this an observed event or a stated anchor
     * with no event behind it", orthogonal to provenance. */
    val KIND_OPTIONS = listOf("OBSERVED", "ASSERTED")
    const val KIND_OBSERVED = "OBSERVED"
    const val KIND_ASSERTED = "ASSERTED"

    // ---- MaintenanceSchedule fields -------------------------------------------------------------
    const val FIELD_MS_VEHICLE = "vehicle"
    const val FIELD_MS_SERVICE_NAME = "serviceName"
    const val FIELD_MS_INTERVAL_MILES = "intervalMiles"
    const val FIELD_MS_INTERVAL_MONTHS = "intervalMonths"
    const val FIELD_MS_INTERVAL_SOURCE = "intervalSource"
    const val FIELD_MS_NEVER_DONE = "neverDone"

    /** One schema per record type - see [com.kevin.legion.engine.pantry.PantryAspectSeeder.Schema]
     * for the identical multi-record-type shape (field ids are `AUTOINCREMENT`, not known at
     * compile time). */
    data class RecordSchema(val recordTypeId: Long, val fieldIds: Map<String, Long>)

    data class Schema(
        val aspectId: Long,
        val vehicle: RecordSchema,
        val serviceHistory: RecordSchema,
        val maintenanceSchedule: RecordSchema,
    )

    /** Idempotent at every granularity - see
     * [com.kevin.legion.engine.dates.DatesAspectSeeder.ensureSeeded]'s doc comment for the exact
     * mechanism (matched by name at each level, not a single top-level flag). */
    suspend fun ensureSeeded(context: Context): Schema {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        val aspectId = db.aspectDao().listActive().find { it.name == ASPECT_NAME }?.id
            ?: db.aspectDao().insert(
                Aspect(name = ASPECT_NAME, icon = "fleet", color = "", position = 4, createdAt = now, updatedAt = now),
            )

        val vehicleTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == VEHICLE_RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = VEHICLE_RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )
        val serviceHistoryTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == SERVICE_HISTORY_RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = SERVICE_HISTORY_RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )
        val maintenanceScheduleTypeId = db.recordTypeDao().listByAspect(aspectId).find { it.name == MAINTENANCE_SCHEDULE_RECORD_TYPE_NAME }?.id
            ?: db.recordTypeDao().insert(
                RecordType(aspectId = aspectId, name = MAINTENANCE_SCHEDULE_RECORD_TYPE_NAME, createdAt = now, updatedAt = now),
            )

        suspend fun ensureField(
            recordTypeId: Long,
            existing: Map<String, FieldDef>,
            name: String,
            type: FieldType,
            required: Boolean,
            position: Int,
            config: String? = null,
        ): Long {
            existing[name]?.let { return it.id }
            return db.fieldDefDao().insert(
                FieldDef(
                    recordTypeId = recordTypeId,
                    name = name,
                    type = type,
                    required = required,
                    position = position,
                    config = config,
                    ownerPluginId = OWNER_PLUGIN_ID,
                    locked = required,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }

        // ---- Vehicle --------------------------------------------------------------------------
        val existingVehicleFields = db.fieldDefDao().forRecordType(vehicleTypeId).associateBy { it.name }
        val vehicleFieldIds = mutableMapOf<String, Long>()
        vehicleFieldIds[FIELD_NAME] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_NAME, FieldType.TEXT, required = true, position = 0)
        vehicleFieldIds[FIELD_MAKE] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_MAKE, FieldType.TEXT, required = true, position = 1)
        vehicleFieldIds[FIELD_MODEL] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_MODEL, FieldType.TEXT, required = true, position = 2)
        vehicleFieldIds[FIELD_YEAR] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_YEAR, FieldType.NUMBER, required = true, position = 3)
        vehicleFieldIds[FIELD_TRIM] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_TRIM, FieldType.TEXT, required = false, position = 4)
        vehicleFieldIds[FIELD_ENGINE] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_ENGINE, FieldType.TEXT, required = false, position = 5)
        vehicleFieldIds[FIELD_CONFIRMED] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_CONFIRMED, FieldType.BOOLEAN, required = true, position = 6)
        // "Odometer" from ticket 21's core-chain instruction - the driver-reported reading plus
        // when it was given. Not required: a freshly registered car can legitimately have neither.
        vehicleFieldIds[FIELD_ODOMETER_BASELINE] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_ODOMETER_BASELINE, FieldType.NUMBER, required = false, position = 7)
        vehicleFieldIds[FIELD_ODOMETER_BASELINE_AT] = ensureField(vehicleTypeId, existingVehicleFields, FIELD_ODOMETER_BASELINE_AT, FieldType.DATETIME, required = false, position = 8)

        // ---- ServiceHistory ---------------------------------------------------------------------
        val existingShFields = db.fieldDefDao().forRecordType(serviceHistoryTypeId).associateBy { it.name }
        val shFieldIds = mutableMapOf<String, Long>()
        shFieldIds[FIELD_SH_VEHICLE] = ensureField(
            serviceHistoryTypeId, existingShFields, FIELD_SH_VEHICLE, FieldType.REFERENCE, required = true, position = 0,
            // BLOCK, not CASCADE - instruction 1's explicit rule: deleting a car with history must
            // refuse, not silently take its service history down with it. See carve doc.
            config = FieldConfig.serializeReference(vehicleTypeId, DeletePolicy.BLOCK),
        )
        shFieldIds[FIELD_SH_SERVICE_NAME] = ensureField(serviceHistoryTypeId, existingShFields, FIELD_SH_SERVICE_NAME, FieldType.TEXT, required = true, position = 1)
        shFieldIds[FIELD_SH_MILEAGE] = ensureField(serviceHistoryTypeId, existingShFields, FIELD_SH_MILEAGE, FieldType.NUMBER, required = false, position = 2)
        // Not wired as primaryDueDateFieldId - a logged or asserted service is a past, settled
        // event, not an upcoming due date. Same scope cut as every prior wave's own event-date field.
        shFieldIds[FIELD_SH_SERVICE_DATE] = ensureField(serviceHistoryTypeId, existingShFields, FIELD_SH_SERVICE_DATE, FieldType.DATETIME, required = false, position = 3)
        shFieldIds[FIELD_SH_COST] = ensureField(serviceHistoryTypeId, existingShFields, FIELD_SH_COST, FieldType.MONEY_CENTS, required = false, position = 4)
        shFieldIds[FIELD_SH_KIND] = ensureField(
            serviceHistoryTypeId, existingShFields, FIELD_SH_KIND, FieldType.CHOICE, required = true, position = 5,
            config = FieldConfig.serializeChoice(KIND_OPTIONS),
        )

        // costCents is the one genuinely monetary figure on this record type - promote it, same
        // shape as every prior wave's own primaryAmountFieldId promotion.
        val shType = db.recordTypeDao().getById(serviceHistoryTypeId)!!
        if (shType.primaryAmountFieldId != shFieldIds[FIELD_SH_COST]) {
            db.recordTypeDao().update(shType.copy(primaryAmountFieldId = shFieldIds[FIELD_SH_COST], updatedAt = now))
        }

        // ---- MaintenanceSchedule ------------------------------------------------------------------
        val existingMsFields = db.fieldDefDao().forRecordType(maintenanceScheduleTypeId).associateBy { it.name }
        val msFieldIds = mutableMapOf<String, Long>()
        msFieldIds[FIELD_MS_VEHICLE] = ensureField(
            maintenanceScheduleTypeId, existingMsFields, FIELD_MS_VEHICLE, FieldType.REFERENCE, required = true, position = 0,
            // Same BLOCK reasoning as ServiceHistory.vehicle, generalised from instruction 1's
            // wording (a maintenance schedule is exactly as much "history you'd lose" as a logged
            // service) - see the carve doc's own note flagging this as the one generalisation made.
            config = FieldConfig.serializeReference(vehicleTypeId, DeletePolicy.BLOCK),
        )
        msFieldIds[FIELD_MS_SERVICE_NAME] = ensureField(maintenanceScheduleTypeId, existingMsFields, FIELD_MS_SERVICE_NAME, FieldType.TEXT, required = true, position = 1)
        msFieldIds[FIELD_MS_INTERVAL_MILES] = ensureField(maintenanceScheduleTypeId, existingMsFields, FIELD_MS_INTERVAL_MILES, FieldType.NUMBER, required = false, position = 2)
        msFieldIds[FIELD_MS_INTERVAL_MONTHS] = ensureField(maintenanceScheduleTypeId, existingMsFields, FIELD_MS_INTERVAL_MONTHS, FieldType.NUMBER, required = false, position = 3)
        // Plain TEXT, not CHOICE - see the carve doc's field-mapping note: the legacy column's own
        // doc comment argues against baking this into a fixed vocabulary even at the Room level.
        msFieldIds[FIELD_MS_INTERVAL_SOURCE] = ensureField(maintenanceScheduleTypeId, existingMsFields, FIELD_MS_INTERVAL_SOURCE, FieldType.TEXT, required = true, position = 4)
        msFieldIds[FIELD_MS_NEVER_DONE] = ensureField(maintenanceScheduleTypeId, existingMsFields, FIELD_MS_NEVER_DONE, FieldType.BOOLEAN, required = true, position = 5)
        // Deliberately NO lastDoneMileage/lastDoneDate field here - see this object's own class doc
        // and the carve doc's headline finding. That figure lives exclusively in ServiceHistory now.

        return Schema(
            aspectId = aspectId,
            vehicle = RecordSchema(vehicleTypeId, vehicleFieldIds),
            serviceHistory = RecordSchema(serviceHistoryTypeId, shFieldIds),
            maintenanceSchedule = RecordSchema(maintenanceScheduleTypeId, msFieldIds),
        )
    }
}
