package com.kevin.legion.ui.fleet

import android.content.Context
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleController.WriteOutcome

/**
 * The write dispatch behind ticket 11's SERVICE HISTORY screen
 * (`.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`), same split
 * [MaintenanceWrites.kt] already established for ticket 09's screens: the screen files under
 * `ui/fleet/` stay display-only, `ui/FleetScreen.kt`'s state holder calls these, then hands them
 * down as plain suspend lambdas.
 *
 * Both functions are thin wrappers over [VehicleController.editServiceRecordDirect]/
 * `deleteServiceRecordDirect`, which already own the targeted-write-plus-read-back and
 * zero-rows-is-a-real-failure discipline (ticket 05's law) - nothing here duplicates that.
 */

/** The edit dialog's SAVE - see [VehicleController.editServiceRecordDirect]'s own doc for the write shape. */
suspend fun writeEditServiceRecord(context: Context, id: Long, mileage: Int, costCents: Long?): WriteOutcome =
    VehicleController.editServiceRecordDirect(context, id, mileage, costCents)

/**
 * The edit dialog's DELETE - **local only**, see [com.kevin.legion.data.local.ServiceRecord.deleted]'s
 * doc comment for why `service_records`' UNION sync mode makes that structural rather than a
 * limitation of this particular write path. [VehicleController.deleteServiceRecordDirect]'s own
 * returned message already says so in words; this wrapper does not paraphrase it.
 */
suspend fun writeDeleteServiceRecord(context: Context, id: Long): WriteOutcome =
    VehicleController.deleteServiceRecordDirect(context, id)
