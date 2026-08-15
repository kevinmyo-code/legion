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
            return VehicleMatch.Resolved(VehicleController.currentVehicle(context))
        }

        val dao = CarDatabase.getDatabase(context).vehicleDao()
        val active = dao.getAll() // non-archived only, see VehicleDao.getAll's own doc
        val known = active.map { displayName(it) }

        tierMatch(active, query) { it.name.equals(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        tierMatch(active, query) { it.obdMac.equals(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        tierMatch(active, query) { it.model.equals(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }
        tierMatch(active, query) { fullDescription(it).contains(query, ignoreCase = true) }
            ?.let { return it.toMatch(query, known) }

        // Nothing in the active roster - see if the driver is asking about a
        // car they archived, so the refusal can say why rather than lying
        // that it doesn't exist.
        val archived = dao.getAllIncludingArchived()
            .filter { it.archived && it.name.equals(query, ignoreCase = true) }
        if (archived.size == 1) {
            return VehicleMatch.Unknown(requested = "$query (archived - it's hidden from the roster)", known = known)
        }

        return VehicleMatch.Unknown(requested = query, known = known)
    }

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

    /** What a car is called back to the driver - name if it has one, else the make/model. */
    private fun displayName(v: Vehicle): String =
        v.name.takeIf { it.isNotBlank() && it != "this car" } ?: VehicleController.displayLabel(v).ifBlank { "an unnamed car" }
}
