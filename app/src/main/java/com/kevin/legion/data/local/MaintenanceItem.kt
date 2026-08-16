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

/**
 * True whenever the driver did NOT state this interval themselves (`intervalSource != "CONFIRMED"`)
 * AND the item carries an interval on at least one axis - the shared predicate mission-control
 * ticket 16 moved onto the entity from `ui/fleet/FleetRows.kt`'s `isGuessTag`
 * (`.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`),
 * because `vehicle/` and `advisor/` both need the rule now and `vehicle/` must never import
 * `ui.fleet` (`FleetRows.kt` already imports `VehicleController`, so the reverse import would be a
 * dependency cycle). Every consumer - UI, the spoken tools, the digests, the model-facing prompt
 * builders - delegates to this ONE definition; see the shelf entries below for each delegating
 * call site rather than re-deriving the rule locally.
 *
 * **Deliberately three-way, never `== "SEEDED"`.** The original rule (ticket 06 refinement c) only
 * caught `SEEDED`, which was correct back when `SEEDED`/`CONFIRMED` were the only two values on
 * file. Ticket 18 added `LOOKUP` (a populate accept, reviewed but never typed - see
 * [MaintenanceItem.intervalSource]'s own doc) and a two-way test would have sorted it silently into
 * "not a guess", rendering a value that came off a lookup shown to disagree with itself
 * three-of-eight-items across two runs in five minutes as if the driver had typed it - exactly the
 * laundering ticket 18 exists to stop. Testing the NEGATIVE (`!= "CONFIRMED"`) rather than
 * enumerating every non-driver-stated value means a future provenance addition defaults to
 * disclosed rather than defaulting to silent, which is the safer failure mode for a disclosure
 * flag.
 *
 * A `Brake Fluid`/`Brake Pads` orphan (created by `VehicleController.logServiceDirect` with no
 * interval at all) is `SEEDED` by the entity's own column default but has nothing to doubt, and its
 * sub-line already says "no interval on file" honestly - tagging it a guess would be a claim about
 * a number that does not exist. That second clause is unchanged by ticket 18.
 */
val MaintenanceItem.intervalIsUnconfirmed: Boolean
    get() = intervalSource != "CONFIRMED" && (intervalMiles != null || intervalMonths != null)

/**
 * Words a screen, prompt, or spoken sentence can put beside an [intervalIsUnconfirmed] item's
 * value, distinguishing WHICH kind of non-driver-stated provenance it is (ticket 18): a `SEEDED`
 * row is LEGION's own unreviewed guess at a plausible interval, a `LOOKUP` row is a factory-schedule
 * lookup the driver actually reviewed and accepted - a materially different claim, since ticket 18
 * found that lookup disagrees with itself roughly every other run. `null` for `CONFIRMED`, the one
 * case with nothing to disclose - callers that need "you set this" for that case supply it
 * themselves, since that phrase only makes sense in a diff-row context this general-purpose
 * property has no opinion about.
 */
val MaintenanceItem.provenanceWords: String?
    get() = provenanceWordsForSource(intervalSource)

/**
 * [MaintenanceItem.provenanceWords]'s actual logic, keyed on the raw `intervalSource` string rather
 * than a full [MaintenanceItem] - `vehicle/PopulateChangeRow`/`vehicle/PopulatePossibleMatchRow`
 * (`PopulateDrilldown.kt`'s `WouldChangeRow`/`PossibleMatchRow`) only ever carry the ON-FILE row's
 * `currentSource`/`existingSource` as a bare string, not the row itself, so [provenanceWords]
 * delegates here rather than forcing either of those call sites to fabricate a throwaway
 * [MaintenanceItem] just to read one field back off it.
 */
fun provenanceWordsForSource(intervalSource: String): String? = when (intervalSource) {
    "SEEDED" -> "LEGION's guess"
    "LOOKUP" -> "from a factory lookup"
    else -> null
}
