package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.data.local.CarDatabase

/**
 * The build sheet / spend ledger ([BuildEntry]) - mods, parts, repairs,
 * consumables, and general spend on the car. The open-ended companion to the
 * scheduled maintenance brain ([VehicleController]) and the car to-do list (now the "Car" list in
 * [com.kevin.legion.notes.NotesController], since `CarTaskController` was retired when car tasks
 * absorbed into the general list model - `.scratch/notes-lists-calendar/issues/10-*`); together
 * they feed the logbook timeline.
 *
 * No spend-gating (SpendGate was retired 2026-07-31, no replacement built yet) -
 * this controller just returns the raw numbers to whoever calls it.
 */
object BuildSheetController {
    val TYPES = setOf("mod", "part", "repair", "consumable", "other")

    private fun dao(context: Context) = CarDatabase.getDatabase(context).buildEntryDao()

    /**
     * Logs a build-sheet entry. [cost] is optional (null = no figure logged).
     * [vehicleId] is the fleet-wide-voice override (ticket 01, "category B"
     * stored-data tool) - null means the active car, unchanged; last param
     * per the ticket's convention.
     */
    suspend fun add(
        context: Context,
        title: String,
        type: String,
        cost: Double?,
        vendor: String = "",
        notes: String = "",
        vehicleId: String? = null,
    ): String {
        val t = title.trim()
        if (t.isBlank()) return "I didn't catch what to log - say it again?"
        val vehicle = VehicleController.vehicleFor(context, vehicleId)
        val mileage = VehicleController.currentMileage(vehicle).takeIf { it > 0 }
        dao(context).insert(
            BuildEntry(
                vehicleId = vehicle.obdMac,
                type = normalizeType(type),
                title = t,
                vendor = vendor.trim(),
                cost = cost,
                date = System.currentTimeMillis(),
                mileage = mileage,
                notes = notes.trim(),
            )
        )
        return "Logged \"$t\" on your build sheet."
    }

    /**
     * Build history (newest first), optionally filtered to one category.
     * [vehicleId] override (ticket 01) - null means the active car.
     */
    suspend fun history(context: Context, type: String = "", vehicleId: String? = null): List<BuildEntry> {
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
        val t = type.trim().lowercase()
        return if (t.isBlank()) dao(context).getForVehicle(id)
        else dao(context).getForVehicleByType(id, normalizeType(t))
    }

    /**
     * Grand total spend = build entries + logged maintenance costs.
     * [vehicleId] override (ticket 01) - null means the active car. Not
     * currently exercised by any Live tool (`get_spend` is not in ticket 01's
     * category B list), added for the controller-threading consistency the
     * ticket's §2 asks for.
     *
     * [ServiceRecordDao.totalCost] returns cents (ticket 11, CLAUDE.md §4 rule 3);
     * this function's own return type stays dollars to match [BuildEntry.cost] and
     * every existing caller, so the cents figure is divided by 100 right here at the
     * boundary rather than propagated further as an ambiguous raw number.
     */
    suspend fun totalSpend(context: Context, vehicleId: String? = null): Double {
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
        return dao(context).totalSpend(id) + FleetEngineStore.totalCostForVehicle(context, id) / 100.0
    }

    /**
     * Spend broken down by category, including a "maintenance" bucket from
     * service records. [vehicleId] override (ticket 01) - see [totalSpend]'s
     * doc for why this is currently unwired to any tool.
     */
    suspend fun spendByCategory(context: Context, vehicleId: String? = null): Map<String, Double> {
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
        val map = linkedMapOf<String, Double>()
        for (type in TYPES) {
            val sum = dao(context).spendByType(id, type)
            if (sum > 0) map[type] = sum
        }
        // Cents -> dollars at the boundary, same reasoning as totalSpend above.
        val maintenance = FleetEngineStore.totalCostForVehicle(context, id) / 100.0
        if (maintenance > 0) map["maintenance"] = maintenance
        return map
    }

    /** Maps a loose hint from the assistant to one of [TYPES]. */
    fun normalizeType(raw: String): String {
        val c = raw.trim().lowercase()
        return when {
            c in TYPES -> c
            c.contains("mod") || c.contains("upgrade") || c.contains("install") || c.contains("swap") -> "mod"
            c.contains("repair") || c.contains("fix") -> "repair"
            c.contains("consum") || c.contains("fluid") || c.contains("oil") || c.contains("filter") -> "consumable"
            c.contains("part") || c.contains("component") -> "part"
            else -> "other"
        }
    }
}
