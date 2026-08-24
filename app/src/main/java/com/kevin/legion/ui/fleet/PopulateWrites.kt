package com.kevin.legion.ui.fleet

import android.content.Context
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.PopulateChangeRow
import com.kevin.legion.vehicle.PopulatePossibleMatchRow
import com.kevin.legion.vehicle.PopulateRestoreRow
import com.kevin.legion.vehicle.VehicleController.WriteOutcome

/**
 * The write dispatch behind ticket 14's populate diff screen
 * (`.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`), same split as
 * `ui/fleet/MaintenanceWrites.kt`: the diff screen itself is display-only (rows in, a suspend
 * callback out), every function here is a thin wrapper over an existing or new targeted DAO write,
 * and every one follows ticket 05's law - a targeted write returns its affected row count, and a
 * zero is surfaced in words as a real [WriteOutcome] failure, never silently swallowed.
 *
 * **Every accepted row lands `LOOKUP`, never `SEEDED`** - ticket 06 decision b's original call
 * ("any driver action that names the value moves it to CONFIRMED... accepting a populate diff
 * row") is SUPERSEDED here by ticket 18, `.scratch/fleet-maintenance/issues/18-the-factory-lookup-is-not-stable-enough-to-diff-against.md`.
 * That ticket found the factory-schedule lookup itself is not stable enough to diff against - four
 * runs on the same car, minutes apart, disagreed with each other on three of eight items - so a
 * populate accept was writing `CONFIRMED`, the tag meaning "the driver stated this value", off the
 * back of a genuinely flaky retrieval. `LOOKUP` exists to stop that laundering: **reviewing a
 * proposal and tapping accept is not the same act as the driver naming a figure themselves**, even
 * though both used to collapse onto the same provenance tag. [writeAddItem]'s hand-typed rows are
 * still `CONFIRMED` from the moment they are typed - that path is untouched, because there the
 * driver IS the source of the number, not a lookup the driver merely reviewed.
 */

/**
 * WOULD ADD's accept (ticket 14): a genuine insert of [item] (already canonicalized by
 * [com.kevin.legion.vehicle.VehicleController.fetchFactorySchedule]) via
 * [com.kevin.legion.data.local.MaintenanceItemDao.insertIgnore] - **not** [com.kevin.legion.data.local.MaintenanceItemDao.upsert]
 * (ticket 14 review, BLOCKING 2, `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`).
 * `upsert` REPLACEs the whole row, and a WOULD ADD candidate is computed at diff-load time and can
 * sit un-accepted while the driver reviews the rest of the diff - if a row with the same
 * `(vehicleId, serviceName)` appears in that window (a sync merge, a voice `log_service` orphan, or
 * a near-miss the matching gap missed), tapping ADD on the stale candidate must never silently
 * clobber whatever that concurrent write left, anchor and provenance included. No anchor is set
 * here either way (the factory schedule has no opinion about when THIS car last had the work
 * done); `intervalSource` is forced to `LOOKUP` (ticket 18, superseding the old CONFIRMED call -
 * see this file's own doc comment) regardless of what [item] carries, since [item] is a fresh
 * candidate straight off the lookup and has never been through any other write path.
 *
 * [insertIgnore][com.kevin.legion.data.local.MaintenanceItemDao.insertIgnore] returns `-1` on a
 * suppressed conflict rather than performing the insert - checked here the same way every other
 * targeted write on this map checks its affected row count (ticket 05's law), and surfaced in words
 * rather than reported as a silent overwrite.
 */
suspend fun writePopulateAdd(context: Context, vehicleId: String, item: MaintenanceItem): WriteOutcome {
    val now = System.currentTimeMillis()
    val rowId = FleetEngineStore.insertIgnore(context,
        item.copy(
            vehicleId = vehicleId, intervalSource = "LOOKUP", lastDoneMileage = null, lastDoneDate = null,
            neverDone = false, deleted = false, updatedAt = now,
        )
    )
    return if (rowId == -1L) {
        WriteOutcome(false, "${item.serviceName} is already on file - check WOULD CHANGE.")
    } else {
        WriteOutcome(true, "Added ${item.serviceName}.")
    }
}

/**
 * WOULD CHANGE's accept (ticket 14): [row]'s PROPOSED values, written unconditionally via
 * [com.kevin.legion.data.local.MaintenanceItemDao.setIntervals] - never merged against whatever is
 * currently on file the way [com.kevin.legion.vehicle.VehicleController.setMaintenanceInterval]
 * merges a voice edit's single named axis. That merge exists there to protect an axis the driver
 * never MENTIONED from being nulled by accident; here [row] already carries the full proposed
 * tuple, shown on screen before this is ever called, so writing exactly what was shown - including
 * nulling an axis the factory schedule does not state - is the accept, not an accident.
 *
 * `source = "LOOKUP"` regardless of [row]'s `currentSource` (ticket 18, superseding the old
 * CONFIRMED call - see this file's own doc comment): this call happens because a driver looked at
 * "on file: X, proposed: Y" and chose Y, but Y is still a figure the factory lookup produced, not
 * one the driver named - the review is real, the authorship of the number is not the driver's.
 */
suspend fun writePopulateChange(context: Context, vehicleId: String, row: PopulateChangeRow): WriteOutcome {
    val now = System.currentTimeMillis()
    val written = FleetEngineStore.setIntervals(context, vehicleId, row.serviceName, row.proposedMiles, row.proposedMonths, "LOOKUP", now)
    return if (written == 0) {
        WriteOutcome(false, "I found ${row.serviceName} a moment ago but couldn't write to it just now - it may have just been removed.")
    } else {
        WriteOutcome(true, "Updated ${row.serviceName}.")
    }
}

/**
 * NOT IN FACTORY SCHEDULE's accept (ticket 14): the same tombstone [writeDeleteItem] already uses
 * (ticket 07's soft delete). A thin alias rather than a re-export so this file's own callers never
 * have to know the two "delete an item" affordances on this map share one implementation - they do,
 * deliberately, since a delete is a delete regardless of which screen offered it.
 */
suspend fun writePopulateDelete(context: Context, vehicleId: String, serviceName: String): WriteOutcome =
    writeDeleteItem(context, vehicleId, serviceName)

/**
 * WOULD RESTORE's accept (ticket 14): "you deleted this - add it back?" answered yes. Un-tombstones
 * the row AND writes [row]'s proposed interval in the SAME targeted UPDATE
 * ([com.kevin.legion.data.local.MaintenanceItemDao.restore]) - never a silent skip (the item stays
 * gone with no record the factory still lists it) and never a silent restore (nothing here runs
 * without this function being called, which only happens on an explicit tap).
 *
 * `source = "LOOKUP"` (ticket 18, superseding the old CONFIRMED call): the restored interval is
 * [row]'s proposed value, straight off the factory lookup, reviewed and accepted rather than typed.
 */
suspend fun writePopulateRestore(context: Context, vehicleId: String, row: PopulateRestoreRow): WriteOutcome {
    val now = System.currentTimeMillis()
    val written = FleetEngineStore.restore(context, vehicleId, row.serviceName, row.proposedMiles, row.proposedMonths, "LOOKUP", now)
    return if (written == 0) {
        WriteOutcome(false, "Couldn't restore ${row.serviceName} - it may not have been on file after all.")
    } else {
        WriteOutcome(true, "Restored ${row.serviceName}.")
    }
}

/**
 * POSSIBLE MATCH's "same thing" accept (ticket 14 review, BLOCKING 1b): the driver has confirmed
 * [row.factoryName][PopulatePossibleMatchRow.factoryName] and [row.existingName][PopulatePossibleMatchRow.existingName]
 * name the same job, so this writes the factory-proposed interval onto the EXISTING row by name -
 * exactly [writePopulateChange]'s write, just reached from a near-miss guess instead of an exact
 * canonical match. Never touches [row.factoryName] as a key; [row.existingName] is what is actually
 * on file and the only name this may write through.
 */
suspend fun writePopulateMergeMatch(context: Context, vehicleId: String, row: PopulatePossibleMatchRow): WriteOutcome =
    writePopulateChange(
        context, vehicleId,
        PopulateChangeRow(
            serviceName = row.existingName,
            currentMiles = row.currentMiles,
            currentMonths = row.currentMonths,
            currentSource = row.existingSource,
            proposedMiles = row.proposedMiles,
            proposedMonths = row.proposedMonths,
        ),
    )

/**
 * POSSIBLE MATCH's "no, add as new" accept (ticket 14 review, BLOCKING 1b): the driver has looked
 * at the near-miss guess and rejected it, so this is [writePopulateAdd] under
 * [row.factoryName][PopulatePossibleMatchRow.factoryName] - a genuine new row, verbatim, the near-miss
 * comparator's guess explicitly overridden rather than silently trusted. Goes through the same
 * insert-only path as an ordinary WOULD ADD, so a row that appeared under this exact name in the
 * same review window still cannot be clobbered (BLOCKING 2, [writePopulateAdd]'s own doc).
 */
suspend fun writePopulateAddAsNew(context: Context, vehicleId: String, row: PopulatePossibleMatchRow): WriteOutcome =
    writePopulateAdd(
        context, vehicleId,
        MaintenanceItem(
            vehicleId = vehicleId, serviceName = row.factoryName,
            intervalMiles = row.proposedMiles, intervalMonths = row.proposedMonths,
        ),
    )
