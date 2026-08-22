package com.kevin.legion.ui.fleet

import android.content.Context
import com.kevin.legion.vehicle.BuildSheetController
import com.kevin.legion.vehicle.VehicleController.WriteOutcome

/**
 * The write dispatch behind ticket 07's BUILD SHEET screen
 * (`.scratch/command-center/issues/07-build-sheet-screen.md`), same split every other `ui/fleet/`
 * screen already uses: the screen file stays display-only, `ui/FleetScreen.kt`'s state holder calls
 * this, then hands it down as a plain suspend lambda.
 *
 * Thin wrapper over [BuildSheetController.add] - the EXACT function `service/LiveToolbox.kt`'s
 * `logBuildEntry` dispatch calls for the `log_build_entry` voice tool, so an entry logged by hand
 * and one logged by voice are the same write, never two paths that could drift (ADR 0035's "not a
 * second implementation" clause).
 *
 * [BuildSheetController.add] itself returns a bare `String` either way - no [WriteOutcome], no
 * success verdict - because its only caller until now (`logBuildEntry`) always guards the blank
 * title case itself, BEFORE calling the controller, and reports `success = true` unconditionally
 * past that guard. This wrapper reproduces that same guard rather than trusting the controller's
 * return string to imply success, so a blank title reaches the screen as a real `success = false`
 * (CLAUDE.md's ticket-05 law: a write that could silently do nothing must say so).
 */
suspend fun writeLogBuildEntry(
    context: Context,
    vehicleId: String,
    title: String,
    type: String,
    /**
     * Long cents (CLAUDE.md §4 rule 3) - the dialog parses the typed dollar string to cents first
     * for precision, same "parse precise, convert at the boundary" shape
     * [com.kevin.legion.vehicle.VehicleController.logServiceDirect]'s own doc describes.
     * [BuildSheetController.add]'s `cost` param is still a dollar `Double` - [BuildEntry.cost]'s own
     * stored column, unchanged by this ticket (widening it to cents is a schema migration this
     * ticket does not ask for) - so the conversion back to dollars happens right here, at this
     * function's own boundary, never left for the screen to do inline.
     */
    costCents: Long?,
    vendor: String,
    notes: String,
): WriteOutcome {
    val trimmed = title.trim()
    if (trimmed.isBlank()) {
        return WriteOutcome(false, "This needs a title before it can go on the build sheet.")
    }
    val cost = costCents?.let { it / 100.0 }
    val message = BuildSheetController.add(
        context,
        title = trimmed,
        type = type,
        cost = cost,
        vendor = vendor.trim(),
        notes = notes.trim(),
        vehicleId = vehicleId,
    )
    return WriteOutcome(true, message)
}
