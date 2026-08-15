package com.kevin.legion.ui.fleet

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
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
 */
suspend fun writeSetAnchor(context: Context, vehicleId: String, serviceName: String, mode: AnchorMode, mileage: Int?, date: Long?): WriteOutcome {
    val now = System.currentTimeMillis()
    val dao = CarDatabase.getDatabase(context).maintenanceItemDao()
    val written = when (mode) {
        AnchorMode.NEVER_DONE -> dao.setNeverDone(vehicleId, serviceName, now)
        // Both anchors null is the legitimate "I don't know" state setAnchor's own doc names -
        // never a silent no-op read as "nothing changed".
        AnchorMode.DONT_KNOW -> dao.setAnchor(vehicleId, serviceName, null, null, now)
        AnchorMode.DONE_AT -> dao.setAnchor(vehicleId, serviceName, mileage, date, now)
    }
    return if (written == 0) {
        WriteOutcome(false, "I found $serviceName a moment ago but couldn't write to it just now - it may have just been removed.")
    } else {
        WriteOutcome(true, "Updated $serviceName.")
    }
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
    CarDatabase.getDatabase(context).maintenanceItemDao().upsert(item)
    return WriteOutcome(true, "Added $trimmed.")
}

/**
 * CONFIRM-ALL's write (ticket 06 decision 2): [writeSetInterval] looped over every item the caller
 * has already shown the driver ([confirmableSeededItems]'s own list, rendered in full BEFORE this is
 * ever called - see `FullScheduleScreen`'s confirm dialog). Each call is independent and its own
 * [WriteOutcome], so one item disappearing mid-loop (a concurrent delete) fails only that one row
 * rather than the whole batch - the caller surfaces any `success == false` entries in words rather
 * than assuming a clean sweep.
 */
suspend fun writeConfirmAll(context: Context, vehicleId: String, items: List<MaintenanceItem>): List<WriteOutcome> =
    items.map { VehicleController.setMaintenanceInterval(context, it.serviceName, it.intervalMiles, it.intervalMonths, vehicleId) }
