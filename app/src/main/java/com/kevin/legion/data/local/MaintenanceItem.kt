package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * A recurring maintenance task for a vehicle (e.g. "Oil Change" every 5,000
 * miles / 6 months). Intervals are seeded from an online lookup based on the
 * vehicle's make/model/year, and [lastDoneMileage]/[lastDoneDate] update as
 * the driver logs completed work.
 */
@Entity(tableName = "maintenance_items", primaryKeys = ["vehicleId", "serviceName"])
data class MaintenanceItem(
    val vehicleId: String,
    val serviceName: String,
    val intervalMiles: Int? = null,
    val intervalMonths: Int? = null,
    val lastDoneMileage: Int? = null,
    val lastDoneDate: Long? = null,
    // Last-modified epoch ms for cross-device sync last-write-wins (S1). The Kotlin
    // default stamps new rows; an EDIT must re-stamp via copy(updatedAt =
    // System.currentTimeMillis()). DEFAULT '0' mirrors the migration.
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),
    // "Never been done" is a KNOWN, actionable fact ("I've never rotated the tires
    // on this car") and is deliberately distinct from an UNKNOWN anchor (both
    // lastDone* fields null because the driver never said anything). neverDone
    // items are always overdue; unknown items are never treated as due at all -
    // conflating the two used to make a high-mileage car's entire schedule read
    // as due the moment it was seeded, because every freshly-looked-up interval
    // starts with null anchors. See VehicleController.dueItems/unknownItems.
    @ColumnInfo(defaultValue = "0") val neverDone: Boolean = false,
    // Who put this interval on the schedule: "SEEDED" (LEGION's LLM lookup guessed
    // it, unreviewed), "LOOKUP" (a factory-schedule lookup the driver reviewed and
    // accepted via the populate diff), or "CONFIRMED" (the driver typed it, or
    // stated it directly - VehicleController's set_maintenance_interval tool,
    // MaintenanceWrites' hand-add, or an item-detail confirm).
    // Ticket 06 (`.scratch/fleet-maintenance/issues/06-*`): an LLM-guessed interval
    // never enters CLAUDE.md §4's reconciliation gate - there is nothing to reconcile
    // it against - so this is deliberately NOT [IngestMethod] (that vocabulary
    // describes what survived the gate). Plain TEXT, no enum column type: CLAUDE.md
    // §5's "widening an enum stored as TEXT is not a migration" - LOOKUP was added
    // ticket 18 at zero schema cost this exact way, and a future FACTORY state (a
    // bundled schedule, declined for now) costs no schema change either.
    // Every row migrated in at v20 defaults to SEEDED, correctly - all 54 rows on
    // Kevin's phone were LLM-produced and updatedAt cannot reveal authorship (its
    // Kotlin default stamps construction). DEFAULT 'SEEDED' mirrors the migration.
    //
    // LOOKUP exists because ticket 18 found the factory lookup itself is not stable
    // enough to diff against - four runs on the same car, minutes apart, disagreed
    // with each other on three of eight items - so a populate accept must never be
    // laundered into CONFIRMED, the tag meaning "the driver stated this value". A
    // driver reviewing and accepting a proposal is a different act from a driver
    // naming a figure, and the schema now says so.
    @ColumnInfo(defaultValue = "SEEDED") val intervalSource: String = "SEEDED",
    // Soft-delete tombstone (ticket 07, v19->v20). maintenance_items syncs
    // Mode.LWW/naturalPk (SyncEngine.kt) so a hard DELETE cannot propagate - the
    // other device's un-deleted copy would win the next merge and resurrect the
    // row. Reuses the same pattern car_tasks/places have carried since B19: the
    // sync snapshot deliberately does NOT filter on this column (a tombstone must
    // ship to Drive to propagate), every other reader (DAOs, controllers, tools,
    // UI) DOES filter deleted = 0. A tombstoned row is still a row, so re-seeding
    // via insertAll's @Insert(IGNORE) cannot resurrect a deleted item - the
    // existing (tombstoned) row blocks the IGNORE, which is correct and now
    // deliberate rather than a happy accident. DEFAULT '0' mirrors the migration.
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
