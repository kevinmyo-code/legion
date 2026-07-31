package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.BuildEntry
import com.kevin.legion.data.local.CarDatabase

/**
 * The build sheet / spend ledger ([BuildEntry]) - mods, parts, repairs,
 * consumables, and general spend on the car. The open-ended companion to the
 * scheduled maintenance brain ([VehicleController]) and the to-do list
 * ([CarTaskController]); together they feed the logbook timeline.
 *
 * Logging is always allowed (it's the owner's own data). READING the money layer
 * (per-entry costs + the grand total) is gated by [com.kevin.legion.ai.SpendGate] -
 * this controller just returns the raw numbers; the caller (LiveToolbox / the UI)
 * decides whether to reveal them. The *history* (what/when) is never gated.
 */
object BuildSheetController {
    val TYPES = setOf("mod", "part", "repair", "consumable", "other")

    private fun dao(context: Context) = CarDatabase.getDatabase(context).buildEntryDao()
    private fun serviceDao(context: Context) = CarDatabase.getDatabase(context).serviceRecordDao()

    /** Logs a build-sheet entry. [cost] is optional (null = no figure logged). */
    suspend fun add(
        context: Context,
        title: String,
        type: String,
        cost: Double?,
        vendor: String = "",
        notes: String = "",
    ): String {
        val t = title.trim()
        if (t.isBlank()) return "I didn't catch what to log - say it again?"
        val vehicle = VehicleController.currentVehicle(context)
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
        // Confirmation deliberately omits the dollar figure (the money layer is
        // gated/hidden); Zero acknowledges what was logged, not how much.
        return "Logged \"$t\" on your build sheet."
    }

    /** Build history (newest first), optionally filtered to one category. */
    suspend fun history(context: Context, type: String = ""): List<BuildEntry> {
        val vehicleId = VehicleController.currentVehicle(context).obdMac
        val t = type.trim().lowercase()
        return if (t.isBlank()) dao(context).getForVehicle(vehicleId)
        else dao(context).getForVehicleByType(vehicleId, normalizeType(t))
    }

    /** Grand total spend = build entries + logged maintenance costs. */
    suspend fun totalSpend(context: Context): Double {
        val vehicleId = VehicleController.currentVehicle(context).obdMac
        return dao(context).totalSpend(vehicleId) + serviceDao(context).totalCost(vehicleId)
    }

    /** Spend broken down by category, including a "maintenance" bucket from service records. */
    suspend fun spendByCategory(context: Context): Map<String, Double> {
        val vehicleId = VehicleController.currentVehicle(context).obdMac
        val map = linkedMapOf<String, Double>()
        for (type in TYPES) {
            val sum = dao(context).spendByType(vehicleId, type)
            if (sum > 0) map[type] = sum
        }
        val maintenance = serviceDao(context).totalCost(vehicleId)
        if (maintenance > 0) map["maintenance"] = maintenance
        return map
    }

    /** Maps a loose hint from Zero to one of [TYPES]. */
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
