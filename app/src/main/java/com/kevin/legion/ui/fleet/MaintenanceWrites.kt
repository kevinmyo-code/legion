package com.kevin.legion.ui.fleet

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleController.WriteOutcome

/**
 * The write dispatch behind ticket 09's ITEM DETAIL and FULL SCHEDULE screens
 * (`.scratch/fleet-maintenance/issues/09-the-maintenance-surface-rebuilt.md`). Deliberately NOT
 * inside `ui/fleet/FleetDrilldowns.kt` - this file's own doc comment states every screen there is
 * "display-only: state/rows in, a callback out, no controller or DAO reference", matching every
 * other drilldown/content split in the app, and these functions are exactly the controller/DAO side
 * of that split. `ui/FleetScreen.kt`'s state holder calls these (never the drilldown composables
 * directly), then hands them down as plain suspend lambdas.
 *
 * **Every function here follows ticket 05's law**: a targeted write returns its affected row count,
 * and a zero is surfaced in words as a real [WriteOutcome] failure, never silently swallowed and
 * never asserted as success the write did not actually perform. Nothing here adds a DAO method or
 * new write logic - every one of these is a thin wrapper composing
 * [com.kevin.legion.data.local.MaintenanceItemDao]'s existing `setIntervals`/`setAnchor`/
 * `setNeverDone`/`softDelete`/`upsert`, or delegates outright to
 * [com.kevin.legion.vehicle.VehicleController.setMaintenanceInterval] (which already owns the
 * merge-never-null-out-an-axis-you-didn't-touch logic, the `CONFIRMED` provenance stamp, and the
 * read-back).
 */

/**
 * An EXISTING item's interval edit, or the GUESS-tag confirm affordance (which is this same call
 * made with the item's OWN current values - see [com.kevin.legion.vehicle.VehicleController.setMaintenanceInterval]'s
 * doc: any driver action that names the value moves it to `CONFIRMED`, ticket 06 decision (b), and
 * naming the current value unchanged is still naming it). [serviceName] here is always an EXISTING
 * item's own stored name (never free text needing near-duplicate detection - that only applies to
 * [writeAddItem]), so [VehicleController.looksLikeExistingItem]'s internal self-match is exact.
 */
suspend fun writeSetInterval(context: Context, vehicleId: String, serviceName: String, miles: Int?, months: Int?): WriteOutcome =
    VehicleController.setMaintenanceInterval(context, serviceName, miles, months, vehicleId)

/**
 * The three-way anchor picker's write (ticket 07): dispatches to
 * [com.kevin.legion.data.local.MaintenanceItemDao.setNeverDone] or `setAnchor` by [mode], never a
 * read-modify-write - both DAO queries touch only the anchor columns, so a concurrent interval edit
 * elsewhere on the same row can never be lost between a read here and this write (the exact defect
 * class ticket 05 exists to close). [written] is checked, never assumed, per ticket 05's law.
 *
 * **Ticket 07 (command-center) addendum: `costCents`, the optional cost step.** Before this
 * addendum this function touched ONLY the anchor - the survey behind ticket 07 named that a bug
 * ("`log_service` by hand moves the maintenance clock but never creates a `service_records` row, so
 * cost never enters by hand"), and the fix was checked against this file's own doc comments first
 * per that ticket's instruction. What was actually documented, on [writeDeleteItem] alone, is that
 * a DELETE never touches `service_records` - correct, and unrelated: that is about not destroying
 * existing history when an item is removed, not about whether marking an item DONE may add to it.
 * [ItemDetailScreen]'s own doc comment additionally states its rendered service-history list is
 * "read-only... ticket 11 owns the full history screen and cost" - but that sentence is about
 * EDITING an already-shown record ([ServiceHistoryScreen]'s own edit dialog owns that, deliberately,
 * so a record is never editable from two screens at once), not about CREATING a new one when a
 * DONE_AT save happens. Neither comment reasons about creation at all; the omission was real, not a
 * documented decision this addendum overrides.
 *
 * Only fires on [AnchorMode.DONE_AT] with a non-null [costCents] - "skipping cost stays legal"
 * (ticket 07): [AnchorMode.NEVER_DONE]/[AnchorMode.DONT_KNOW] never did the work described by a
 * cost, and a `null` cost on DONE_AT is the same "no figure logged" state every other cost field in
 * this app already renders honestly rather than as `$0.00`. The record's `mileage`/`date` are the
 * SAME values just written to the anchor - [mileage] falls back to the vehicle's own current live
 * reading only when the driver left it blank (mirrors
 * [VehicleController.logServiceDirect]'s own mileage capture for a record with no explicit one);
 * [date] falls back to "now" for the same reason. This is a direct
 * [com.kevin.legion.data.local.ServiceRecordDao.insert] - the exact write `log_service`'s own
 * [VehicleController.logServiceDirect] performs for the record half of its own two-part write - not
 * a call to [VehicleController.logServiceDirect] itself, because that function ALSO unconditionally
 * resets the anchor to the vehicle's current mileage/now, which would silently discard a driver-typed
 * historical mileage or date this picker explicitly supports and [writeSetAnchor] already wrote
 * moments above. There is no `notes` field on [ServiceRecord] to carry a notes step into - see that
 * entity's own file for its full column list - so this addendum is cost-only; widening the entity
 * for notes is a schema change this ticket does not ask for and is not made here.
 */
suspend fun writeSetAnchor(
    context: Context,
    vehicleId: String,
    serviceName: String,
    mode: AnchorMode,
    mileage: Int?,
    date: Long?,
    costCents: Long? = null,
): WriteOutcome {
    val now = System.currentTimeMillis()
    val db = CarDatabase.getDatabase(context)
    val dao = db.maintenanceItemDao()
    val written = when (mode) {
        AnchorMode.NEVER_DONE -> dao.setNeverDone(vehicleId, serviceName, now)
        // Both anchors null is the legitimate "I don't know" state setAnchor's own doc names -
        // never a silent no-op read as "nothing changed".
        AnchorMode.DONT_KNOW -> dao.setAnchor(vehicleId, serviceName, null, null, now)
        // The date is RESOLVED, never taken raw: mileage and date are independently optional
        // fields on this form, and a mileage-only save used to write a null date straight over
        // one a real logged service had established. That is how the Jeep ended up claiming
        // 227,483 mi with no date while a 12 Aug record sat in service_records, and how the
        // assistant came to deny an oil change it could show on screen (hands-and-senses 28).
        // resolveDoneAtDate re-derives the date from the backing record when one exists and
        // never overrides a date the user actually typed.
        AnchorMode.DONE_AT -> dao.setAnchor(
            vehicleId,
            serviceName,
            mileage,
            VehicleController.resolveDoneAtDate(context, vehicleId, serviceName, mileage, date),
            now,
        )
    }
    if (written == 0) {
        return WriteOutcome(false, "I found $serviceName a moment ago but couldn't write to it just now - it may have just been removed.")
    }
    if (mode == AnchorMode.DONE_AT && costCents != null) {
        val recordMileage = mileage ?: VehicleController.currentMileage(VehicleController.vehicleFor(context, vehicleId))
        db.serviceRecordDao().insert(
            ServiceRecord(vehicleId = vehicleId, serviceName = serviceName, mileage = recordMileage, date = date ?: now, costCents = costCents),
        )
    }
    return WriteOutcome(true, "Updated $serviceName.")
}

/**
 * Soft-deletes an item (ticket 07 decision 1: a real delete via the existing tombstone, never a
 * hard `DELETE` - see [com.kevin.legion.data.local.MaintenanceItemDao.softDelete]'s own doc for why
 * `maintenance_items`' LWW sync mode requires this). Service history for the item survives
 * untouched by construction - this never touches `service_records`.
 */
suspend fun writeDeleteItem(context: Context, vehicleId: String, serviceName: String): WriteOutcome {
    val now = System.currentTimeMillis()
    val written = CarDatabase.getDatabase(context).maintenanceItemDao().softDelete(vehicleId, serviceName, now)
    return if (written == 0) {
        WriteOutcome(false, "Couldn't delete $serviceName - it may have already been removed.")
    } else {
        WriteOutcome(true, "Deleted $serviceName.")
    }
}

/**
 * ADD ITEM's write (ticket 07 decision 2): a GENUINE insert via
 * [com.kevin.legion.data.local.MaintenanceItemDao.upsert] - the DAO's own doc names this as the
 * method that "survives for genuine inserts only" - carrying [name] VERBATIM as the primary key's
 * `serviceName` half, never through [VehicleController.canonicalizeServiceName]. This is
 * deliberately NOT [writeSetInterval]/[VehicleController.setMaintenanceInterval]: that function
 * internally re-matches the typed name against the existing schedule and, on a collision, EDITS the
 * matched row - exactly the outcome ticket 07's "storage is verbatim, detection is comparator-only"
 * ruling forbids once the driver has already been warned about a near-duplicate and chosen to add it
 * anyway. The near-duplicate warning itself is computed by the CALLER
 * ([VehicleController.looksLikeExistingItem], a pure comparison, no IO) before this function is ever
 * invoked - this function never second-guesses that decision.
 *
 * At least one of an interval or an anchor must be given - mirrors
 * [VehicleController.setMaintenanceInterval]'s own "I need a mileage interval or a time interval"
 * refusal shape, since none of this map's existing write functions can express a row with neither an
 * interval nor any anchor information at all (ticket 07's own question 4, left open in its answer;
 * this call site's own explicit choice, stated here rather than silently allowing a fully-empty row).
 * `intervalSource = "CONFIRMED"` unconditionally: the driver typed this row directly, which is
 * ticket 06 decision (b)'s own definition of an already-confirmed value - it is never `SEEDED`
 * (nothing here was guessed) and so it can never carry a `[GUESS]` tag even before an interval exists
 * to qualify one.
 */
suspend fun writeAddItem(
    context: Context,
    vehicleId: String,
    name: String,
    miles: Int?,
    months: Int?,
    mode: AnchorMode,
    mileage: Int?,
    date: Long?,
): WriteOutcome {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) {
        return WriteOutcome(false, "This needs a name.")
    }
    if (miles == null && months == null && mode == AnchorMode.DONT_KNOW) {
        return WriteOutcome(false, "Give me an interval, or something about when it was last done, before I add this.")
    }
    val item = MaintenanceItem(
        vehicleId = vehicleId,
        serviceName = trimmed,
        intervalMiles = miles,
        intervalMonths = months,
        lastDoneMileage = if (mode == AnchorMode.DONE_AT) mileage else null,
        lastDoneDate = if (mode == AnchorMode.DONE_AT) date else null,
        neverDone = mode == AnchorMode.NEVER_DONE,
        intervalSource = "CONFIRMED",
    )
    // insertIgnore, never upsert. `upsert` is @Insert(REPLACE) - a whole-row overwrite - and this
    // path is reached from a form the driver may have had open while something else wrote the same
    // (vehicleId, serviceName): a voice log_service creating an orphan, a populate accept, a sync
    // merge. Tapping ADD would then silently replace that row wholesale, taking its anchor and its
    // provenance with it.
    //
    // Worse, the no-op law cannot catch it: REPLACE always reports a write, so a row-count check is
    // structurally blind here. That is why the fix is the insert strategy and not another guard.
    //
    // Found by review of ticket 14's identical bug in writePopulateAdd, which correctly reported
    // this sibling as out of its own scope rather than reaching into this file. Fixed here on the
    // same reasoning that governs the rest of this map: a known instance of a bug just fixed
    // elsewhere is not a smaller bug for being known.
    val rowId = CarDatabase.getDatabase(context).maintenanceItemDao().insertIgnore(item)
    if (rowId == -1L) {
        return WriteOutcome(false, "$trimmed is already on the schedule - open it to change its interval.")
    }
    return WriteOutcome(true, "Added $trimmed.")
}

/**
 * CONFIRM-ALL's write (ticket 06 decision 2): [writeSetInterval] looped over every item the caller
 * has already shown the driver ([confirmableItems]'s own list, rendered in full BEFORE this is
 * ever called - see `FullScheduleScreen`'s confirm dialog). Each call is independent and its own
 * [WriteOutcome], so one item disappearing mid-loop (a concurrent delete) fails only that one row
 * rather than the whole batch - the caller surfaces any `success == false` entries in words rather
 * than assuming a clean sweep.
 */
suspend fun writeConfirmAll(context: Context, vehicleId: String, items: List<MaintenanceItem>): List<WriteOutcome> =
    items.map { VehicleController.setMaintenanceInterval(context, it.serviceName, it.intervalMiles, it.intervalMonths, vehicleId) }
