package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.VehicleController
import com.kevin.legion.vehicle.VehicleResolver
import org.json.JSONObject

/**
 * `manage_vehicle` (add, rename, remove, switch, adapter, and the rest of the fleet-ownership
 * surface), split out of [LiveToolbox] (shrink pass, 2026-11-22) along the [EngineToolbox.dispatch]
 * pattern: [LiveToolbox.dispatch] runs its guards first, then asks this object, which answers the
 * name it owns and returns null for every other.
 */
object VehicleManageToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "manage_vehicle" -> manageVehicle(context, args)
        else -> null
    }

    /**
     * The whole fleet-ownership surface behind one tool (see the registration's
     * comment for why it is one tool and not five).
     *
     * `add` is the only action that does not resolve a vehicle first - it is the
     * only one that must not be able to reach an existing row. Every other action
     * goes through [withResolvedVehicle], so an unknown or ambiguous car comes
     * back as a question to the driver rather than landing on whichever car
     * happened to be active. That distinction IS the bug this tool fixes.
     */
    private suspend fun manageVehicle(context: Context, args: JSONObject): JSONObject {
        val year = args.optInt("year").takeIf { it >= 1900 }
        val make = args.optString("make").takeIf { it.isNotBlank() }
        val model = args.optString("model").takeIf { it.isNotBlank() }
        val trim = args.optString("trim").takeIf { it.isNotBlank() }
        val name = args.optString("name").takeIf { it.isNotBlank() }

        val action = args.optString("action")

        // VehicleResolver.resolveVehicle treats a blank `vehicle` as "the active
        // car". For a READ that is a sane default; for an edit it is precisely
        // the failure being fixed here - an unnamed "correct" would silently
        // rewrite whatever car happened to be active, which is how the Outlander
        // became an F-150. Every editing action must name its target out loud.
        if (action != "add" && args.optString("vehicle").isBlank()) {
            // 2026-11-15 voice eval: "which cars do I have on file" called switch_vehicle with no car.
            // Nothing changes without a named car; the refusal says where a read goes instead.
            return result(false, needsACar(action))
        }

        return when (action) {
            "add" -> result(
                success = make != null && model != null,
                message = VehicleController.addVehicle(
                    context,
                    year = year ?: 0,
                    make = make.orEmpty(),
                    model = model.orEmpty(),
                    trim = trim.orEmpty(),
                    name = name.orEmpty(),
                ),
            )
            "correct" -> withResolvedVehicle(context, args) {
                result(true, VehicleController.correctVehicle(
                    context, it.obdMac, year = year, make = make, model = model, trim = trim,
                ))
            }
            "rename" -> withResolvedVehicle(context, args) {
                if (name == null) result(false, "What would you like me to call it?")
                else result(true, VehicleController.correctVehicle(context, it.obdMac, name = name))
            }
            "switch" -> withResolvedVehicle(context, args) { switchTo(context, it) }
            "adapter" -> withResolvedVehicle(context, args) { adapterIn(context, it) }
            "archive" -> withResolvedVehicle(context, args) {
                VehicleController.archive(context, it.obdMac)
                result(true, "Archived the ${VehicleController.label(it)}. Nothing was deleted - its history is still there.")
            }
            // Deliberately NOT withResolvedVehicle: VehicleResolver only ever
            // matches against the non-archived roster (see its own comment), so
            // routing unarchive through it makes the one action whose target is
            // archived by definition permanently unreachable.
            "unarchive" -> {
                val query = args.optString("vehicle")
                // Matching, not labelling - kept on displayLabel's trim-inclusive spec deliberately
                // (ticket 04's label rule is about how a car is NAMED to the driver, not what a
                // spoken query is matched against; narrowing this to the label string would drop
                // trim as a matchable word, e.g. "the Limited", for no gain).
                val hits = VehicleController.allVehiclesIncludingArchived(context)
                    .filter { it.archived }
                    .filter {
                        it.name.equals(query, true) || it.model.equals(query, true) ||
                            VehicleController.displayLabel(it).contains(query, true)
                    }
                when (hits.size) {
                    1 -> {
                        VehicleController.unarchive(context, hits[0].obdMac)
                        result(true, "The ${VehicleController.label(hits[0])} is back on the roster.")
                    }
                    0 -> result(false, "I don't have an archived car matching \"$query\".")
                    else -> result(false, "\"$query\" matches more than one archived car - " +
                        "${hits.joinToString(", ") { VehicleController.label(it) }}. Which one?")
                }
            }
            else -> result(false, "I don't know how to \"$action\" a car.")
        }
    }

    /**
     * `manage_vehicle switch` once the car is resolved. With the dongle connected this also answers
     * "which car is it in" (2026-11-15), and can refuse (a VIN conflict), so the flag is the
     * outcome's, never a hardcoded true. Already the selected car with no dongle connected: nothing
     * to change, and the result says so.
     */
    private suspend fun switchTo(context: Context, car: Vehicle): JSONObject {
        // `changed` and `vehicle` (the app's label) let code say the outcome in words (2026-11-19).
        val label = VehicleController.label(car)
        val noDongle = com.kevin.legion.vehicle.DongleCarController.state.value == null
        if (ActiveVehicle.selected(context) == car.obdMac && noDongle) {
            return result(true, "The $label was already the selected car; nothing changed.")
                .put("changed", false).put("vehicle", label)
        }
        val outcome = VehicleController.switchToOutcome(context, car.obdMac)
        val out = result(outcome.success, outcome.message).put("changed", outcome.changed == true).put("vehicle", label)
        // A pick never answers the which-car question; while it is open, his turn still carries it (R2-2).
        val heard = kotlin.coroutines.coroutineContext[RelativeDay.TurnDates] != null
        return if (heard) com.kevin.legion.vehicle.AdapterBinding.annotate(context, out) else out
    }

    /** The refusal for a `manage_vehicle` [action] called with no car named. */
    private fun needsACar(action: String): String = when (action) {
        "switch" -> SWITCH_NEEDS_A_CAR
        "adapter" -> "Nothing changed: say which car the adapter is in."
        else -> "Which car do you mean? Tell me and I'll $action it."
    }

    /** `manage_vehicle adapter`: [VehicleController.adapterIn], with what code needs to say it. */
    private suspend fun adapterIn(context: Context, car: Vehicle): JSONObject {
        val r = VehicleController.adapterIn(context, car.obdMac)
        return result(r.outcome.success, r.outcome.message).put("changed", r.outcome.changed == true)
            .put("vehicle", VehicleController.label(car)).put("readings_moved", r.readingsMoved)
    }

    private const val SWITCH_NEEDS_A_CAR = "Nothing was switched: switch_vehicle changes which car is selected " +
        "and needs the car's name. To say which cars he has, call ask ask_fleet."
}
