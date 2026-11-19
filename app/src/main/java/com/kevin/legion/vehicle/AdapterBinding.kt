package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.Vehicle
import org.json.JSONObject

/**
 * "Which car is the OBD adapter in", as the VOICE meets it (2026-11-17 phone logs: the adapter's car
 * was unknown; "reassign that adapter to the <car on file>" became `car clear_codes vehicle=...`
 * three times, and "this car is a <car on file>" became `register_vehicle`, whose refusal said
 * "switch to it" and was never passed on).
 *
 * The one action stays `car switch_vehicle` ([DongleCarController.answer] through
 * [VehicleController.switchToOutcome]; the same controller as the Cars screen's question card and
 * the notification's buttons, ADR 0035). The binding is the CONNECTION's (Kevin: "i have one adapter
 * that i move between cars"): his answer binds this connection and teaches the car's fingerprint
 * ([CarFingerprint]); the next connect of that car is recognised by it, and a disconnect drops it.
 * This object holds the words that route the other doors to it and the rule for asking once.
 * Pure but for what was already said ([note], [noteFor]), which is process memory.
 */
object AdapterBinding {
    /** The adapter's car is unknown: what a live-car tool result tells the model to do, once. */
    const val ASK_ONCE = "The OBD adapter is plugged into a car he has not named yet, so these readings " +
        "are held, not filed. Ask him once, in a few words: which car is it in? His answer is car " +
        "switch_vehicle with that car (it holds for this connection; the car is recognised next time)."

    /** Already asked on this connection: the result says so, so the model does not ask again. */
    const val ASKED = "The OBD adapter's car is still unnamed; he was already asked which car it is in. " +
        "If he names one, that is car switch_vehicle."

    /** The connection he was already asked on ([DongleCarController.connectionKey]), spoken or in a result. */
    @Volatile private var askedOn: String? = null

    /** The connection a "Looks like the X" was already said for. */
    @Volatile private var recognisedSaidFor: String? = null

    /** The adapter's car was asked about on [connection] (the connect-time spoken ask counts too). */
    fun markAsked(connection: String) {
        askedOn = connection
    }

    /** The note for a live-car result while the adapter's car is unknown: [ASK_ONCE] once a connection. */
    @Synchronized
    fun note(connection: String?): String {
        if (connection != null && askedOn == connection) return ASKED
        askedOn = connection
        return ASK_ONCE
    }

    /** Test seam. */
    internal fun resetForTest() {
        askedOn = null
        recognisedSaidFor = null
    }

    /**
     * The note a live-car result carries for [state] (the connection's car), or null: unknown, the ask
     * ([note]); recognised by its fingerprint only, once per connection, "say once: looks like the X".
     * [labels] are car id to its name.
     */
    @Synchronized
    fun noteFor(state: DongleCar?, labels: Map<String, String>, connection: String?): String? =
        when {
            state is DongleCar.Held -> note(connection)
            state is DongleCar.OnCar && state.basis == DongleCar.Basis.ASSUMED &&
                recognisedSaidFor != connection -> {
                recognisedSaidFor = connection
                val name = labels[state.carId] ?: "car on file"
                "This connection was recognised as the $name by how its computer answers, not by a VIN. Say " +
                    "it once, in a few words (\"Looks like the $name.\"). If he says it is another car, that " +
                    "is car switch_vehicle with that car."
            }
            else -> null
        }

    /**
     * [response] (a live-car tool's result) with the `adapter_car` note when one is due on this
     * connection. A refusal that already says the car is unknown gets none.
     */
    suspend fun annotate(context: Context, response: JSONObject): JSONObject {
        if (response.optString("error") == UNKNOWN_CAR_ERROR) return response
        val car = DongleCarController.state.value
        val labels = if (car is DongleCar.OnCar) DongleCarController.labels(context) else emptyMap()
        noteFor(car, labels, DongleCarController.connectionKey())?.let { response.put("adapter_car", it) }
        return response
    }

    /**
     * Marks a live-car call already being annotated, so the dispatcher can run the tool once more
     * inside it and add the note on the way out without passing a marker in the tool's arguments.
     */
    class Annotating : kotlin.coroutines.AbstractCoroutineContextElement(Annotating) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<Annotating>
    }

    /**
     * Clearing codes needs a car he named or a VIN proved, never an unknown or only recognised one
     * (Kevin, 2026-11-17), on voice and on the Faults screen alike. Null when it may go ahead (or
     * nothing is connected: the caller says so in its own words).
     */
    fun clearRefusal(state: DongleCar?, connected: Boolean): String? = when {
        // Connected but the car not decided yet (the VIN read takes up to 12 s): never a clear.
        connected && state == null ->
            "Nothing was cleared: the adapter is still working out which car it is in. Ask again in a moment."
        state is DongleCar.Held ->
            "Nothing was cleared: the OBD adapter is in a car he has not named yet. Ask him which car it is in " +
                "(car switch_vehicle), then ask again."
        state is DongleCar.OnCar && state.basis == DongleCar.Basis.ASSUMED ->
            "Nothing was cleared: this car was only recognised by how it answers, not named. Ask him to say " +
                "which car the adapter is in (car switch_vehicle), then ask again."
        else -> null
    }

    /** [clearRefusal] for the live connection now. */
    fun clearRefusalNow(): String? = clearRefusal(DongleCarController.state.value, ObdBluetoothManager.isConnected)

    /**
     * "This car is a <year make model>" while the adapter's car is unknown: the one car on file it
     * names, or null (none, or more than one: the refusal asks instead of guessing).
     */
    fun carOnFileNamed(cars: List<Vehicle>, year: Int, make: String, model: String): Vehicle? {
        val m = make.trim()
        val mo = model.trim()
        if (mo.isEmpty()) return null
        val hits = cars.filter { v ->
            !v.archived &&
                v.model.trim().equals(mo, ignoreCase = true) &&
                (m.isEmpty() || v.make.trim().equals(m, ignoreCase = true)) &&
                (year < MIN_YEAR || v.year < MIN_YEAR || v.year == year)
        }
        return hits.singleOrNull()
    }

    private const val MIN_YEAR = 1900

    /** Car writes that default to "this car" when no car is named. */
    private val THIS_CAR_WRITES = setOf(
        "set_odometer", "log_service", "log_past_service", "set_maintenance_interval", "log_build_entry",
    )

    /**
     * A write to "this car" ([THIS_CAR_WRITES], no `vehicle` named) while the adapter is connected to a
     * car that is unknown or only recognised: refused, so nothing lands on a guessed car. Null: go ahead.
     */
    fun unnamedCarWriteRefusal(tool: String, namedVehicle: String): String? = when {
        tool !in THIS_CAR_WRITES || namedVehicle.isNotBlank() -> null
        !ObdBluetoothManager.isConnected || !DongleCarController.hasLiveQuestion() -> null
        else -> "Nothing was saved: the OBD adapter is in a car he has not named yet, so I don't know which " +
            "car this is for. Ask him which car, then do it with that car's name."
    }

    /** The error code of [unknownCarRefusal]'s result. */
    const val UNKNOWN_CAR_ERROR = "adapter_car_unknown"

    /**
     * A tool named [requested] while the adapter is in a car nobody has named: nothing was read or
     * changed, and the way to say which car it is.
     */
    fun unknownCarRefusal(requested: String): String =
        "Nothing was read or changed: the OBD adapter is plugged into a car he has not named yet, so I can't " +
            "tell whether it is the $requested. If he said it is in the $requested (\"this is the " +
            "$requested\", \"the adapter is in the $requested\", \"reassign the adapter\"), call car " +
            "switch_vehicle vehicle=$requested first, then ask again. Otherwise ask him which car it is in. " +
            "Never clear codes for this."
}
