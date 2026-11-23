package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.assistant.cards.ToolCards
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.theCar
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.RecallCheckResult
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleSpecController
import com.kevin.legion.vehicle.VinDecoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * The car record reads and the one-shot register (`list_vehicles`, `lookup_vin`, `get_specs`,
 * `check_recalls`, `register_vehicle`), split out of [LiveToolbox] (shrink pass, 2026-11-22) along
 * the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards first, then asks this
 * object, which answers the names it owns and returns null for every other.
 */
object VehicleRecordsToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "lookup_vin" -> lookupVin(context)
        "get_specs" -> withResolvedVehicle(context, args) { getSpecs(context, it.obdMac) }
        "check_recalls" -> withResolvedVehicle(context, args) { checkRecalls(context, it, args) }
        "register_vehicle" -> VehicleController.registerDirect(
            context, args.optInt("year"), args.optString("make"), args.optString("model")
        ).let { result(it.success, it.message) }
        "list_vehicles" -> listVehicles(context)
        else -> null
    }

    /**
     * Every non-archived car (fleet-wide voice, ticket 01 §3): name, year,
     * make, model, trim, whether it's the active one, whether the OBD dongle
     * is connected to it right now, and last-known odometer. This is the tool
     * that makes the fleet knowable at all - see its declaration's doc.
     */
    private suspend fun listVehicles(context: Context): JSONObject {
        val vehicles = com.kevin.legion.vehicle.FleetEngineStore.getAll(context)
        val activeId = ActiveVehicle.current(context)
        // The car the dongle is IN (2026-11-09 audit): ActiveVehicle.connectedCarId, never the
        // dongle's MAC against obdMac, which read a hand-added car as unplugged mid-drive.
        val connectedId = ActiveVehicle.connectedCarId(context)
        val arr = JSONArray()
        for (v in vehicles) {
            // Raw v.name deliberately, not VehicleController.label - this is a decomposed tuple
            // (name/year/make/model/trim as separate fields, ticket 04's label rule doesn't apply
            // to the individual raw facts), unlike the single "vehicle" identifier field other
            // tools below send when there is no sibling year/make/model to decompose into.
            val o = JSONObject()
                .put("name", v.name)
                .put("year", v.year)
                .put("make", v.make)
                .put("model", v.model)
                .put("trim", v.trim)
                .put("active", v.obdMac == activeId)
                .put("dongleConnected", connectedId != null && v.obdMac == connectedId)
            // Ticket 10: this JSON goes straight to the model, which then speaks it - a bare Int
            // here would let it state a possibly-5-15%-low estimate back as fact. mileageLabel
            // already carries its own bare/estimate split ("227,900 mi" vs. "about 227,900 mi -
            // estimated, last confirmed 3 days ago"), so the string itself is the caveat.
            val mileageLabel = VehicleController.mileageLabel(v)
            if (mileageLabel.isNotBlank()) o.put("odometer", mileageLabel)
            // memory-and-vault ticket 13: the plate is ordinary data (ruling 5), said when asked.
            com.kevin.legion.vehicle.VehiclePlateController.plateOf(context, v.obdMac)?.let { o.put("plate", it) }
            arr.put(o)
        }
        return JSONObject().put("success", true).put("count", vehicles.size).put("vehicles", arr)
    }

    /**
     * Reads the VIN off the OBD port and decodes it ([VinDecoder]) to year/make/
     * model/trim, for Zero to read back and confirm before saving via
     * register_vehicle. Does NOT save - the driver is the source of truth, and
     * VIN decode (vPIC) can be partial on imports.
     */
    private suspend fun lookupVin(context: Context): JSONObject {
        if (!ObdBluetoothManager.isConnected) {
            return result(success = false,
                message = "The OBD adapter isn't connected, so I can't read the VIN - plug it in and try again.")
        }
        val decoded = VinDecoder.fromObd()
            ?: return result(success = false, message = "I couldn't read a usable VIN from the port - " +
                "this car may not report one (common on older or non-US cars). You can just tell me the year, make, and model.")
        // Populate the build-details encyclopedia (specs) from the same VIN; the
        // identity facts below still need driver confirmation before register_vehicle.
        // 2026-11-15: never onto a car the VIN does not belong to (one dongle, several cars).
        val refusal = VehicleSpecController.obdVinRefusal(context, decoded.vin)
        if (refusal == null) VehicleSpecController.refreshFromVin(context, decoded.vin)
        return JSONObject()
            .put("success", true)
            .put("vin", decoded.vin)
            .put("year", decoded.year)
            .put("make", decoded.make)
            .put("model", decoded.model)
            .put("trim", decoded.trim)
            .put(
                "note",
                if (refusal == null) {
                    "These are read-only. Read them back and ask the user to confirm, then call " +
                        "register_vehicle to save. The full specs were saved to the build-details encyclopedia."
                } else {
                    "These are read-only. Nothing was saved: $refusal"
                },
            )
    }

    /**
     * Reads a car's stored factory specs ([VehicleSpecController]) for Zero to
     * read out. [vehicleId] is the resolved fleet-wide-voice override (ticket
     * 01) - null means the active car.
     */
    private suspend fun getSpecs(context: Context, vehicleId: String? = null): JSONObject {
        val spec = VehicleSpecController.current(context, vehicleId)
            ?: return result(success = false, message = "No specs on file yet - run a VIN lookup, or fill them in under Logbook, Specs.")
        val o = JSONObject().put("success", true)
        spec.engineCylinders?.let { o.put("engineCylinders", it) }
        spec.displacementL?.let { o.put("displacementLiters", it) }
        spec.engineHp?.let { o.put("horsepower", it) }
        putIfSet(o, "engineConfiguration", spec.engineConfig)
        putIfSet(o, "fuelType", spec.fuelType)
        putIfSet(o, "transmission", listOf(spec.transmissionSpeeds, spec.transmissionStyle).filter { it.isNotBlank() }.joinToString(" "))
        putIfSet(o, "drivetrain", spec.driveType)
        putIfSet(o, "bodyClass", spec.bodyClass)
        spec.doors?.let { o.put("doors", it) }
        putIfSet(o, "series", spec.series)
        putIfSet(o, "manufacturer", spec.manufacturer)
        putIfSet(o, "assembledIn", listOf(spec.plantCity, spec.plantCountry).filter { it.isNotBlank() }.joinToString(", "))
        putIfSet(o, "paintColor", spec.paintColor)
        putIfSet(o, "paintCode", spec.paintCode)
        putIfSet(o, "notes", spec.buildNotes)
        com.kevin.legion.vehicle.VehiclePlateController.plateOf(context, vehicleId ?: ActiveVehicle.current(context))
            ?.let { o.put("plate", it) }
        return o
    }

    private fun putIfSet(o: JSONObject, key: String, value: String) {
        if (value.isNotBlank()) o.put(key, value)
    }

    /**
     * Live NHTSA recall lookup for a car ([VehicleSpecController]); on-request
     * only. [vehicleId] is the resolved fleet-wide-voice override (ticket 01) -
     * null means the active car.
     *
     * Gates on identity-present (year/make/model all set), not
     * [com.kevin.legion.data.local.Vehicle.confirmed] - ticket 12
     * (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`) found this tool refusing on
     * `confirmed` while the proactive startup push read `year/make/model` directly with no check
     * at all, so the same car could get recalls announced unprompted and then refused when asked.
     * [VehicleSpecController.recalls] now owns the one gate both paths share.
     */
    @Suppress("ReturnCount")
    private suspend fun checkRecalls(context: Context, vehicle: Vehicle, args: JSONObject = JSONObject()): JSONObject {
        // 2026-11-03: "It's a 1998 Jeep Cherokee" was said and the car record had no identity, so
        // the check refused. A year/make/model stated in the question fills ONLY the fields the
        // record lacks, the lookup runs on that, and saving it to the car is offered, never done.
        val stated = statedIdentity(vehicle, args.optInt("year", 0), args.optString("make"), args.optString("model"))
        if (stated != null) {
            val recalls = VinDecoder.fetchRecalls(stated.year, stated.make, stated.model)
                ?: return result(success = false, message = "Couldn't reach NHTSA to check recalls right now. Try again in a moment.")
            val label = VehicleController.label(vehicle)
            ToolCards.recalls(vehicle, recalls)
            return recallsJson(recalls)
                .put("checkedAs", "${stated.year} ${stated.make} ${stated.model}")
                .put(
                    "saveOffer",
                    "Checked as the ${stated.year} ${stated.make} ${stated.model} the user said. ${theCar(label)
                        .replaceFirstChar { it.uppercase() }} record is missing its ${stated.filled.joinToString(" and ")}; " +
                        "nothing was saved. Ask whether to save it to the car, and save only on a yes, " +
                        "with car action correct_vehicle.",
                )
        }
        return when (val outcome = VehicleSpecController.recalls(context, vehicle.obdMac)) {
            is RecallCheckResult.IdentityMissing -> result(
                success = false,
                message = "I don't know this car's ${outcome.missing.joinToString(" and ")} yet, " +
                    "so I can't check recalls. Tell me and I'll look it up, or run a VIN lookup.",
            )
            RecallCheckResult.LookupFailed -> result(
                success = false,
                message = "Couldn't reach NHTSA to check recalls right now. Try again in a moment.",
            )
            is RecallCheckResult.Checked ->
                recallsJson(outcome.recalls).also { ToolCards.recalls(vehicle, outcome.recalls) }
        }
    }

    private fun recallsJson(recalls: List<VinDecoder.Recall>): JSONObject {
        val arr = JSONArray()
        for (r in recalls) {
            arr.put(JSONObject()
                .put("campaign", r.campaign)
                .put("component", r.component)
                .put("summary", r.summary)
                .put("remedy", r.remedy))
        }
        return JSONObject().put("success", true).put("count", recalls.size).put("recalls", arr)
    }

    @Suppress("MagicNumber") // a plausible model year, to drop a misheard number
    private val MODEL_YEARS = 1900..2100

    /** [statedIdentity]'s answer: the identity to look up, and which fields came from the question. */
    internal data class StatedIdentity(val year: Int, val make: String, val model: String, val filled: List<String>)

    /**
     * The year/make/model to check [vehicle]'s recalls as, when its record lacks some of them and
     * the question supplied every missing one; null when the record is complete (use it) or the
     * question does not fill the gap (refuse as before). The record's own fields always win.
     * Pure and internal so a test pins it.
     */
    @Suppress("ReturnCount")
    internal fun statedIdentity(vehicle: Vehicle, year: Int, make: String, model: String): StatedIdentity? {
        val missing = com.kevin.legion.vehicle.missingIdentityFields(vehicle)
        if (missing.isEmpty()) return null
        val y = vehicle.year.takeIf { it > 0 } ?: year.takeIf { it in MODEL_YEARS } ?: return null
        val mk = vehicle.make.ifBlank { make.trim() }.ifBlank { return null }
        val md = vehicle.model.ifBlank { model.trim() }.ifBlank { return null }
        return StatedIdentity(y, mk, md, missing)
    }
}
