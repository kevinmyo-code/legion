package com.kevin.legion.vehicle

import android.content.Context
import java.time.Instant
import java.time.ZoneId

/**
 * Fleet spend arithmetic (ticket 11 §4, `.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`,
 * resolved 2026-08-15) - "fleet money has never been summed before, and the four figures below are
 * the first arithmetic ever done on it." Every read here goes through
 * [com.kevin.legion.data.local.ServiceRecordDao], which already filters `deleted = 0` (that
 * interface's own doc comment) - a tombstoned record contributes to nothing computed here, with no
 * extra filtering needed in this file.
 *
 * Deliberately its OWN object rather than folded into [BuildSheetController] or [VehicleController].
 * [BuildSheetController.totalSpend]/`spendByCategory` combine build-sheet spend WITH service-record
 * cost into one number for the build sheet's own purpose (a running grand total); this object
 * answers four DIFFERENT questions ticket 11 asked for that nothing computed before it: does the
 * total figure cover every record or only some of them, does the driver's odometer even support a
 * cost-per-mile figure, which service type is the money actually going to, and which year did it go
 * in - none of which is "add build entries and service records together."
 *
 * `Long` cents throughout (CLAUDE.md §4 rule 3) except [CostPerMile.Value.centsPerMile], which is a
 * derived DISPLAY ratio, never stored and never compared for equality - the one place this file
 * allows a fractional value, for the same reason [BuildSheetController.totalSpend] divides by
 * `100.0` at ITS boundary: a ratio computed for reading, not a monetary amount the reconciliation
 * gate could ever need to check bit-for-bit.
 */
object FleetSpendController {

    /**
     * Total spend, all-time, carrying the coverage ticket 11 §4 requires IN WORDS: how many of the
     * vehicle's non-deleted records actually carry a cost, out of how many exist in total. CLAUDE.md
     * §4 rule 6: a total that silently omits cost-less records is a lie by omission of exactly that
     * shape - [recordsWithCost] is what lets a caller say "no costs logged yet" instead of a
     * misleadingly bare "$0.00" when it is zero (both of Kevin's real records are cost-less today).
     */
    data class SpendTotal(val totalCents: Long, val recordsWithCost: Int, val totalRecords: Int)

    suspend fun totalSpent(context: Context, vehicleId: String): SpendTotal {
        return SpendTotal(
            totalCents = FleetEngineStore.totalCostForVehicle(context, vehicleId),
            recordsWithCost = FleetEngineStore.countWithCostForVehicle(context, vehicleId),
            totalRecords = FleetEngineStore.countForVehicle(context, vehicleId),
        )
    }

    /**
     * Cost per mile - ticket 11 §4: "divides by an odometer that is an ESTIMATE (ticket 10)... on
     * Kevin's Jeep the odometer is currently 0, so this figure must REFUSE IN WORDS rather than
     * divide by zero or render nonsense." [com.kevin.legion.data.local.Vehicle.odometerBaseline]
     * `== 0` is the real "driver has never confirmed an odometer" signal - the SAME check
     * `ui/FleetScreen.kt`'s own `FleetUiState.odometerUnset` uses, and deliberately checked BEFORE
     * [VehicleController.currentMileage], because that figure can read positive even with no real
     * baseline (accumulated trip miles against a still-zero baseline - see `FleetUiState.odometerUnset`'s
     * own doc for why the two are not interchangeable).
     */
    sealed class CostPerMile {
        /**
         * [centsPerMile] is a display ratio, not a stored amount - see this file's own doc comment.
         *
         * [mileageCaveat] is [VehicleController.mileageCaveat]'s phrase for the odometer this ratio
         * was divided BY, or `null` only when that odometer is the driver's own just-typed reading
         * with nothing accrued since. **A caller must render it whenever it is non-null.**
         *
         * Refusing at `odometerBaseline == 0` was never the whole job. Once a baseline exists the
         * figure still divides by an ESTIMATE - ticket 03 measured that estimate at 5-15% low,
         * always in the same direction - and this ratio inherits every bit of that error while
         * looking like a precise two-decimal number. Ticket 11 says it in those words: cost per mile
         * "inherits that caveat and says so". It said so on refusal and not on success, which is the
         * half that matters less. Caught on review, 2026-08-15.
         */
        data class Value(val centsPerMile: Double, val mileageCaveat: String?) : CostPerMile()
        data class Refused(val reason: String) : CostPerMile()
    }

    suspend fun costPerMile(context: Context, vehicleId: String): CostPerMile {
        val vehicle = FleetEngineStore.getByMac(context, vehicleId)
            ?: return CostPerMile.Refused("No car on file yet.")
        if (vehicle.odometerBaseline == 0) {
            return CostPerMile.Refused("Odometer hasn't been confirmed yet - say your mileage before cost per mile means anything.")
        }
        val miles = VehicleController.currentMileage(vehicle)
        if (miles <= 0) {
            return CostPerMile.Refused("No mileage on file yet.")
        }
        val total = totalSpent(context, vehicleId)
        if (total.recordsWithCost == 0) {
            return CostPerMile.Refused("No costs logged yet.")
        }
        return CostPerMile.Value(
            centsPerMile = total.totalCents.toDouble() / miles,
            mileageCaveat = VehicleController.mileageCaveat(vehicle),
        )
    }

    /**
     * Spend grouped by CANONICALISED service name - ticket 11 §4: "groups on `serviceName`, so it
     * inherits the duplicate problem - `Air Filter` and `Air Filter Replacement` would split one
     * category. Group on the canonicalised name via the existing comparator, never by rewriting
     * stored data." [VehicleController.canonicalizeServiceName] is used PURELY as a grouping key
     * here (lower-cased on top of that, so `"Oil Change"` and a hand-typed `"oil change"` still
     * collapse into the same bucket even though the canonicaliser itself only titlecases) - the
     * DISPLAY label returned for each group is one of the group's own real stored names (the first
     * one encountered), never a canonicalised rewrite, matching ticket 07's "storage is verbatim"
     * ruling extended to display. Sorted by spend, descending. A record with no cost contributes
     * nothing to any bucket's sum (it is still counted in [totalSpent]'s own coverage figure).
     */
    suspend fun spendByServiceType(context: Context, vehicleId: String): List<Pair<String, Long>> {
        val records = FleetEngineStore.serviceRecordsForVehicle(context, vehicleId)
        val labelByKey = linkedMapOf<String, String>()
        val centsByKey = linkedMapOf<String, Long>()
        for (record in records) {
            val cost = record.costCents ?: continue
            val key = VehicleController.canonicalizeServiceName(record.serviceName).lowercase()
            labelByKey.putIfAbsent(key, record.serviceName)
            centsByKey[key] = (centsByKey[key] ?: 0L) + cost
        }
        return centsByKey.entries.sortedByDescending { it.value }.map { (key, cents) -> (labelByKey[key] ?: key) to cents }
    }

    /**
     * Spend grouped by calendar year, in the DEVICE-LOCAL zone - [ServiceRecord.date] is a
     * `System.currentTimeMillis()` capture at logging time, never a document-printed date, so this
     * reads through the local-zone convention `util/Dates.kt`'s own doc comment calls out
     * ([com.kevin.legion.util.documentDate] would be wrong here for the same reason it names).
     * Ticket 11 §4: "needs several years to say anything; renders a worded empty state until then" -
     * that wording is the CALLER's job (same split [FleetSpendController.costPerMile]'s refusal
     * message vs. this list's raw data), so this returns whatever years actually have cost, oldest
     * first, with no padding for a year that had none.
     */
    suspend fun spendByYear(context: Context, vehicleId: String): List<Pair<Int, Long>> {
        val records = FleetEngineStore.serviceRecordsForVehicle(context, vehicleId)
        val centsByYear = sortedMapOf<Int, Long>()
        for (record in records) {
            val cost = record.costCents ?: continue
            // .date is type-nullable since v46->v47, but serviceRecordsForVehicle only ever
            // returns OBSERVED rows, which always carry one - see CarToolbelt.serviceHistory's own
            // comment for the same fallback reasoning.
            val year = Instant.ofEpochMilli(record.date ?: 0L).atZone(ZoneId.systemDefault()).year
            centsByYear[year] = (centsByYear[year] ?: 0L) + cost
        }
        return centsByYear.entries.map { it.key to it.value }
    }
}
