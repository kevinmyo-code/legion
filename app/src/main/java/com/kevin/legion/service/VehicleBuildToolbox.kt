package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.service.LiveToolbox.noItem
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.util.shortDate
import com.kevin.legion.vehicle.BuildSheetController
import com.kevin.legion.vehicle.GarageController
import com.kevin.legion.vehicle.GaragePreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * The build sheet and the garage door (`log_build_entry`, `list_build_history`, `get_spend`,
 * `activate_garage`), split out of [LiveToolbox] (shrink pass, 2026-11-22) along the
 * [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards first, then asks this
 * object, which answers the names it owns and returns null for every other.
 */
object VehicleBuildToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "log_build_entry" -> withResolvedVehicle(context, args) { logBuildEntry(context, args, it.obdMac) }
        "list_build_history" -> withResolvedVehicle(context, args) { listBuildHistory(context, args.optString("type"), it.obdMac) }
        "get_spend" -> getSpend(context, args.optString("category"))
        "activate_garage" -> activateGarage(context, args)
        else -> null
    }

    // --- Build sheet / spend ledger -------------------------------------

    /**
     * Logs a build-sheet entry (mod/part/repair/consumable/other), with
     * optional cost. [vehicleId] is the resolved fleet-wide-voice override
     * (ticket 01) - null means the active car.
     */
    private suspend fun logBuildEntry(context: Context, args: JSONObject, vehicleId: String? = null): JSONObject {
        val title = args.optString("title")
        if (title.isBlank()) return noItem("What should I log on the build sheet?")
        val cost = if (args.has("cost") && !args.isNull("cost"))
            args.optDouble("cost").takeIf { !it.isNaN() } else null
        val msg = BuildSheetController.add(
            context,
            title = title,
            type = args.optString("type"),
            cost = cost,
            vendor = args.optString("vendor"),
            notes = args.optString("notes"),
            vehicleId = vehicleId,
        )
        return result(success = true, message = msg)
    }

    /**
     * Reads back build history (what/when, plus cost - ungated, see
     * [getSpend]'s doc). [vehicleId] is the resolved fleet-wide-voice override
     * (ticket 01) - null means the active car.
     */
    private suspend fun listBuildHistory(context: Context, type: String, vehicleId: String? = null): JSONObject {
        val entries = BuildSheetController.history(context, type, vehicleId)
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().put("title", e.title).put("type", e.type).put("date", shortDate(e.date))
            if (e.mileage != null) o.put("mileage", e.mileage)
            if (e.vendor.isNotBlank()) o.put("vendor", e.vendor)
            if (e.notes.isNotBlank()) o.put("notes", e.notes)
            // No currency column on BuildEntry (2026-08-07 currency audit, see getSpend's doc
            // comment) - a cost is still reported when the driver stated one, but never with an
            // invented currency attached.
            if (e.cost != null) {
                o.put("cost", e.cost)
                o.put("currency", JSONObject.NULL)
            }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", entries.size).put("entries", arr)
    }

    /**
     * Reports total / per-category spend. No gating - SpendGate was retired
     * 2026-07-31 with no replacement built yet.
     */
    /**
     * 2026-08-07 currency audit (CLAUDE.md §4, the "money figures must carry their currency"
     * pass): [BuildSheetController]'s `Double`-cost build-sheet entries carry NO currency column
     * at all - unlike [com.kevin.legion.ledger.LedgerTransaction]/[com.kevin.legion.data.local.PantryReceipt],
     * which both do. Per the audit's own instruction, a currency is NEVER invented or guessed
     * from the device locale here - `currency` is emitted as `JSONObject.NULL` with a `note`
     * saying plainly that the source never recorded one, so the model can still say the bare
     * number without silently attaching sterling (or any other currency) to it. This is the
     * traced root cause of the bug this whole pass exists to close: `getLedgerBalance` used to
     * emit a bare `balance` figure the same way, and the English-butler persona filled the gap
     * with pounds every time.
     */
    private suspend fun getSpend(context: Context, category: String): JSONObject {
        val byCat = BuildSheetController.spendByCategory(context)
        val currencyNote = "this figure's source data has no currency recorded - state the " +
            "number without naming a currency"
        val cat = category.trim().lowercase()
        if (cat.isNotBlank()) {
            val amount = byCat[cat] ?: byCat[BuildSheetController.normalizeType(cat)] ?: 0.0
            return JSONObject().put("success", true).put("category", cat).put("spend", amount)
                .put("currency", JSONObject.NULL).put("currency_note", currencyNote)
        }
        val catObj = JSONObject()
        for ((k, v) in byCat) catObj.put(k, v)
        return JSONObject().put("success", true)
            .put("total", BuildSheetController.totalSpend(context))
            .put("byCategory", catObj)
            .put("currency", JSONObject.NULL).put("currency_note", currencyNote)
    }

    /**
     * Dispatches the activate_garage voice tool: a Context/JSONObject-thin
     * wrapper around [GarageController.dispatchVoiceActivate], which owns the
     * actual confirm-gate + door-resolution logic (unit-tested directly in
     * GarageLogicTest, no Context needed there).
     */
    private suspend fun activateGarage(context: Context, args: JSONObject): JSONObject {
        val doorName = args.optString("door").takeIf { it.isNotBlank() }
        val confirmed = args.optBoolean("confirmed", false)
        val doors = GarageController.configuredDoors(context)
        val defaultId = GaragePreferences.defaultDoorId(context)
        val r = GarageController.dispatchVoiceActivate(doors, defaultId, doorName, confirmed) { door ->
            GarageController.activate(context, door)
        }
        return result(r.success, r.message)
    }
}
