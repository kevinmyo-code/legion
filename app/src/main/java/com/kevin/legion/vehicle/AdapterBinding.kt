package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle

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
 * This object holds the words that route the other doors to it, the rule for asking once, and the
 * rule that clearing codes needs a car he named or a VIN proved.
 * Pure but for what was already said ([note], [noteFor]), which is process memory.
 */
object AdapterBinding {
    /** The adapter's car is unknown: what a live-car tool result tells the model to do, once. */
    const val ASK_ONCE = "The OBD adapter is plugged into a car he has not named yet, so these readings " +
        "are held, not filed. Ask him once, in a few words: which car is it in? His answer is car " +
        "switch_vehicle with that car (it holds for this connection; the car is recognised next time)."

    /** Already asked in this window: the result says so, so the model does not ask again. */
    const val ASKED = "The OBD adapter's car is still unnamed; he was already asked which car it is in. " +
        "If he names one, that is car switch_vehicle."

    /** About one conversation; the tool code does not see where a conversation starts. */
    const val ASK_WINDOW_MS = 15L * 60 * 1000

    @Volatile private var askedAtMs = 0L

    /** The note for a live-car result while the adapter's car is unknown: [ASK_ONCE] once a window. */
    @Synchronized
    fun note(now: Long = System.currentTimeMillis()): String {
        if (askedAtMs > 0L && now - askedAtMs in 0 until ASK_WINDOW_MS) return ASKED
        askedAtMs = now
        return ASK_ONCE
    }

    /** Test seam. */
    internal fun resetForTest() {
        askedAtMs = 0L
        recognisedSaidFor = null
    }

    /** The connection a "Looks like the X" was already said for (its recording id). */
    @Volatile private var recognisedSaidFor: String? = null

    /**
     * The note a live-car result carries for [state] (the connection's car), or null: unknown, the ask
     * ([note]); recognised by its fingerprint only, once per connection, "say once: looks like the X".
     * [labels] are car id to its name.
     */
    fun noteFor(state: DongleCar?, labels: Map<String, String>, now: Long = System.currentTimeMillis()): String? =
        when {
            state is DongleCar.Held -> note(now)
            state is DongleCar.OnCar && state.basis == DongleCar.Basis.ASSUMED &&
                recognisedSaidFor != state.recordingId -> {
                recognisedSaidFor = state.recordingId
                val name = labels[state.carId] ?: "car on file"
                "This connection was recognised as the $name by how its computer answers, not by a VIN. Say " +
                    "it once, in a few words (\"Looks like the $name.\"). If he says it is another car, that " +
                    "is car switch_vehicle with that car."
            }
            else -> null
        }

    /**
     * Clearing codes needs a car he named or a VIN proved, never an unknown or only recognised one
     * (Kevin, 2026-11-17). Null when it may go ahead (or nothing is connected: the tool says so).
     */
    fun clearRefusal(state: DongleCar?): String? = when {
        state is DongleCar.Held ->
            "Nothing was cleared: the OBD adapter is in a car he has not named yet. Ask him which car it is in " +
                "(car switch_vehicle), then ask again."
        state is DongleCar.OnCar && state.basis == DongleCar.Basis.ASSUMED ->
            "Nothing was cleared: this car was only recognised by how it answers, not named. Ask him to say " +
                "which car the adapter is in (car switch_vehicle), then ask again."
        else -> null
    }

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
}
