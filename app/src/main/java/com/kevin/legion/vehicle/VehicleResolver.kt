package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle

/**
 * The result of turning whatever the model SAID about a car into an actual
 * [Vehicle] row, or an honest reason it couldn't (fleet-wide voice, ticket 01).
 *
 * Fixed, 3-case shape by design (see [VehicleResolver.resolveVehicle]'s doc):
 * a stray fourth case at some call site would silently fall through a `when`
 * that isn't exhaustive over it, so every caller (`LiveToolbox`) is forced by
 * the compiler to handle all three.
 */
sealed interface VehicleMatch {
    data class Resolved(val vehicle: Vehicle) : VehicleMatch

    /**
     * Named something, matched nothing. Carries the roster so the tool can say
     * what DOES exist rather than a bare "no such car".
     *
     * [requested] is normally the driver's own words verbatim, but
     * [VehicleResolver] also uses this field to say an ARCHIVED car was
     * matched (see its doc) - [VehicleMatch.Unknown] has no separate boolean
     * for that by design (its shape is fixed to these two fields), so the
     * archived note rides along as prose inside [requested] itself, which is
     * still honest: the tool's message is built directly from this string.
     */
    data class Unknown(val requested: String, val known: List<String>) : VehicleMatch

    /** Named something that matched more than one car - never guess between them. */
    data class Ambiguous(val requested: String, val candidates: List<String>) : VehicleMatch

    /**
     * Named NO car, and several are on file with nothing to choose between them: no dongle in
     * one, no car picked, none last connected (2026-11-03). Ask which; never answer about a
     * placeholder. [candidates] are the cars' labels.
     */
    data class NoneChosen(val candidates: List<String>) : VehicleMatch
}

/**
 * One place that turns whatever the model said about a car into a [Vehicle],
 * so the 16 stored-data ("category B", CLAUDE.md-ticket-01 §0) tools don't
 * each invent their own matching. Live-hardware ("category A") tools take no
 * `vehicle` argument at all and never call this for their own reading - see
 * `LiveToolbox`'s `refuseIfNotConnectedCar` for the one place a category A
 * tool DOES consult this, to check a stray argument against the connected car.
 */
object VehicleResolver {

    /**
     * Resolves [spoken] against the roster of non-archived vehicles
     * ([com.kevin.legion.data.local.VehicleDao.getAll]).
     *
     * [spoken] null or blank resolves to the active car
     * ([VehicleController.currentVehicle]) - that default is what keeps every
     * existing utterance ("what's my next service") working unchanged; the
     * driver never has to say a car's name to ask about the one they're in.
     *
     * Otherwise, matched case-insensitively against non-archived vehicles, in
     * this fixed tier order:
     *  1. exact [Vehicle.name]
     *  2. exact [Vehicle.obdMac]
     *  3. exact [Vehicle.model]
     *  4. a contains-match across `"$year $make $model $trim $name"`
     *
     * The FIRST tier that yields exactly one match wins - tier order matters
     * (test 8): an exact name match must win even when a looser, later tier
     * would ALSO have matched a different car. A tier yielding several matches
     * returns [VehicleMatch.Ambiguous] immediately rather than falling through
     * to a looser tier that might paper over the collision - two Outlanders
     * must never silently collapse into one pick. A tier yielding zero matches
     * just moves on to the next tier.
     *
     * Archived cars are excluded from every tier above, matching what
     * `CarsScreen`'s roster shows by default (car manager, 2026-07-16) - a
     * driver is not usually asking about a car they hid. If nothing in the
     * active roster matches AND an archived car matches by exact name, that
     * returns [VehicleMatch.Unknown] with a message saying it's archived
     * rather than a bare "no such car": the driver naming it is evidence it
     * exists to them, and the assistant should say why it isn't answering
     * rather than acting like it never heard of it.
     */
    suspend fun resolveVehicle(context: Context, spoken: String?): VehicleMatch {
        val query = spoken?.trim().orEmpty()
        if (query.isBlank()) {
            // 2026-11-03: with no dongle connected and nothing picked, ActiveVehicle.current fell
            // back to "default", a placeholder with an empty schedule, while a real car sat on
            // file - "nothing on the schedule yet, register the car". The roster decides now.
            val roster = FleetEngineStore.getAll(context)
            return pickDefault(
                roster,
                connectedId = ActiveVehicle.connectedCarId(context),
                selectedId = ActiveVehicle.selected(context),
                lastConnectedId = ActiveVehicle.lastConnected(context),
            ) ?: VehicleMatch.Resolved(VehicleController.currentVehicle(context))
        }

        val active = FleetEngineStore.getAll(context) // non-archived only, see VehicleDao.getAll's own doc
        matchSpoken(active, query)?.let { return it }

        // Nothing in the active roster - see if the driver is asking about a
        // car they archived, so the refusal can say why rather than lying
        // that it doesn't exist.
        val known = active.map { displayName(it) }
        val archived = FleetEngineStore.getAllIncludingArchived(context)
            .filter { it.archived && it.name.equals(query, ignoreCase = true) }
        if (archived.size == 1) {
            return VehicleMatch.Unknown(requested = "$query (archived - it's hidden from the roster)", known = known)
        }

        return VehicleMatch.Unknown(requested = query, known = known)
    }

    /**
     * The car a question that names NO car is about (2026-11-03), from the non-archived [roster]:
     *  1. the car the OBD dongle is plugged into, when it is on file;
     *  2. the car picked on the Fleet tab ([ActiveVehicle.selected]), when it is on file;
     *  3. the only car on file;
     *  4. the car the dongle was last plugged into ([ActiveVehicle.lastConnected]), when on file;
     *  5. a car stored under [VehicleController.DEFAULT_VEHICLE_ID], the old fallback id;
     *  6. otherwise [VehicleMatch.NoneChosen]: ask which.
     *
     * Null means "keep the old behaviour": an empty roster, or a dongle plugged into a car that is
     * not on file (its readings key on its MAC, which [VehicleController.currentVehicle] serves).
     * Pure so a JVM test pins the order.
     */
    @Suppress("ReturnCount")
    internal fun pickDefault(
        roster: List<Vehicle>,
        connectedId: String?,
        selectedId: String?,
        lastConnectedId: String?,
    ): VehicleMatch? {
        if (roster.isEmpty()) return null
        fun onFile(id: String?) = id?.let { want -> roster.firstOrNull { it.obdMac == want } }
        if (connectedId != null) return onFile(connectedId)?.let { VehicleMatch.Resolved(it) }
        val pick = onFile(selectedId)
            ?: roster.singleOrNull()
            ?: onFile(lastConnectedId)
            ?: onFile(VehicleController.DEFAULT_VEHICLE_ID)
        return pick?.let { VehicleMatch.Resolved(it) } ?: VehicleMatch.NoneChosen(roster.map { displayName(it) })
    }

    /**
     * [query] against [roster] in the fixed tier order [resolveVehicle]'s doc lists, plus a word
     * tier last: "the Jeep", "my 98 Cherokee" and "the XJ" match when every word left after
     * dropping "the"/"my"/"our" names the car's year, make, model, trim or nickname. Null when no
     * tier matched anything. Pure so a JVM test pins it.
     */
    @Suppress("ReturnCount") // one return per tier, the fixed order resolveVehicle documents
    internal fun matchSpoken(roster: List<Vehicle>, query: String): VehicleMatch? {
        val known = roster.map { displayName(it) }
        tierMatch(roster, query) { it.name.equals(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        tierMatch(roster, query) { it.obdMac.equals(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        tierMatch(roster, query) { it.model.equals(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        tierMatch(roster, query) { fullDescription(it).contains(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        val words = words(query).filterNot { it in FILLER }
        if (words.isEmpty()) return null
        tierMatch(roster, query) { v -> carWords(v).let { mine -> words.all { it in mine } } }
            ?.let { return it.toMatch(query, known) }
        return null
    }

    /**
     * The one car [question] names, if exactly one is named (2026-11-03): "when's the Jeep due
     * for service" names the Jeep. A car counts as named when any of its own words (nickname,
     * make, model, trim, year or two-digit year) appears in the question; when several are, the
     * one with the most matching words wins, and a tie names none. Pure so a JVM test pins it.
     */
    @Suppress("ReturnCount")
    internal fun mentionedIn(question: String, roster: List<Vehicle>): Vehicle? {
        val asked = words(question).toSet()
        if (asked.isEmpty()) return null
        val hits = roster.map { v -> v to carWords(v).count { it in asked } }.filter { it.second > 0 }
        val best = hits.maxOfOrNull { it.second } ?: return null
        return hits.filter { it.second == best }.singleOrNull()?.first
    }

    private fun words(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 2 }

    /** A car's own words: nickname, make, model, trim, and its year as "1998" and "98". */
    private fun carWords(v: Vehicle): Set<String> {
        val out = words(listOf(v.name, v.make, v.model, v.trim).joinToString(" ")).toMutableSet()
        if (v.year > 0) {
            out.add(v.year.toString())
            out.add((v.year % CENTURY).toString().padStart(2, '0'))
        }
        return out - FILLER
    }

    private const val CENTURY = 100

    /** Words that name no car: never matched on their own. */
    private val FILLER = setOf("the", "my", "our", "a", "an", "car", "truck", "new", "old")

    /**
     * One matching tier: null means "no opinion, try the next tier" (zero
     * hits); a non-null, >1-element list means ambiguous; a non-null,
     * single-element list means resolved. Kept as a tri-state so
     * [resolveVehicle] can `?.let { return ... }` its way down the tiers
     * without a chain of if/else.
     */
    private fun tierMatch(candidates: List<Vehicle>, query: String, predicate: (Vehicle) -> Boolean): List<Vehicle>? {
        val hits = candidates.filter(predicate)
        return if (hits.isEmpty()) null else hits
    }

    /** A one-or-many tier hit list into the matching [VehicleMatch] case. */
    private fun List<Vehicle>.toMatch(query: String, known: List<String>): VehicleMatch =
        when (size) {
            1 -> VehicleMatch.Resolved(single())
            else -> VehicleMatch.Ambiguous(requested = query, candidates = map { displayName(it) })
        }

    /** "$year $make $model $trim $name" - the contains-match tier's search text. */
    private fun fullDescription(v: Vehicle): String =
        listOf(v.year.takeIf { it > 0 }?.toString().orEmpty(), v.make, v.model, v.trim, v.name)
            .joinToString(" ")

    /**
     * What a car is called back to the driver. Delegates entirely to [VehicleController.label]
     * (ticket 04's label rule, `.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`) rather
     * than its own precedence - this function used to be the ONE place in the app with a
     * `"this car"` sentinel filter, everywhere else forgot it, and the fix is one rule everywhere
     * rather than one more site remembering to filter.
     */
    private fun displayName(v: Vehicle): String = VehicleController.label(v)
}
