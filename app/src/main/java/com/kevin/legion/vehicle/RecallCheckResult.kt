package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle

/**
 * True once [vehicle] has enough identity to key a year/make/model lookup - the recall gate
 * (ticket 12, `.scratch/fleet-maintenance/issues/12-a-recall-button.md`). Deliberately NOT
 * [Vehicle.confirmed]: that flag records the driver having *stated* the identity, a different
 * question from whether one exists to look up, and the VIN write-back (ticket 04,
 * [VehicleController.applyDecodedIdentity]) fills year/make/model from a decode without ever
 * setting it - a car identified purely from its own VIN is real enough to key NHTSA by, even
 * though nobody has said "yes, that's my car" out loud. Mirrors [VinDecoder.fetchRecalls]'s own
 * front-door guard exactly, so the two can never disagree about what "enough to look up" means.
 * Pure (no Context/DB) so the gate is unit-testable on its own.
 *
 * Top-level here, not on [VehicleController], only because this file is [RecallCheckResult]'s -
 * the gate the sealed result exists to report on - and `VehicleController.kt` was mid-edit under
 * a different ticket (15) at the time this landed.
 */
fun identityPresent(vehicle: Vehicle): Boolean =
    vehicle.year > 0 && vehicle.make.isNotBlank() && vehicle.model.isNotBlank()

/**
 * Which of year/make/model, in that order, is blank on [vehicle] - empty iff [identityPresent].
 * For the recall button/tool's "identity missing" outcome, which names the specific gap rather
 * than a bare refusal (ticket 12). Pure, same reason as [identityPresent].
 */
fun missingIdentityFields(vehicle: Vehicle): List<String> = buildList {
    if (vehicle.year <= 0) add("year")
    if (vehicle.make.isBlank()) add("make")
    if (vehicle.model.isBlank()) add("model")
}

/**
 * Outcome of [VehicleSpecController.recalls] (ticket 12,
 * `.scratch/fleet-maintenance/issues/12-a-recall-button.md`). Recalls are keyed by
 * year/make/model, not VIN ([VinDecoder.fetchRecalls]'s own doc), despite sitting next to the VIN
 * on the specs screen, and are never stored ([com.kevin.legion.data.local.VehicleSpec] says so
 * deliberately) - every check is a live NHTSA call.
 *
 * Three outcomes that must never collapse into each other. A bare `List<Recall>` cannot tell "this
 * car has no identity to look up" apart from "the lookup ran and genuinely found nothing" - both
 * are an empty list - and that exact collapse is what let the same empty answer come back whether
 * the car was the blank placeholder seed or a real, clean 28-year-old Jeep. Nor can it tell either
 * of those apart from "the HTTP call itself failed". CLAUDE.md §7: network calls degrade
 * gracefully offline, which means every branch here has to be sayable in words.
 */
sealed class RecallCheckResult {
    /**
     * Year, make, or model (or more than one) is blank, so no lookup was even attempted -
     * [missing] names which, in year/make/model order ([missingIdentityFields]), for a caller to
     * say plainly and route to a fix (SYNC ID FROM VIN) rather than silently reporting zero
     * recalls for a car it cannot identify.
     */
    data class IdentityMissing(val missing: List<String>) : RecallCheckResult()

    /**
     * The NHTSA call succeeded and parsed. [recalls] may be empty - that IS the expected answer
     * for most cars most of the time (a 28-year-old Jeep has none), and it must render as a
     * completed check, never as an empty state that looks like a failed load.
     */
    data class Checked(val recalls: List<VinDecoder.Recall>) : RecallCheckResult()

    /**
     * The HTTP call failed (offline, NHTSA unreachable, timeout) or its response was
     * unparseable. Distinct from [Checked] with an empty list on purpose - "the check ran and
     * found nothing" and "the check never ran" are different answers and must never look alike.
     */
    object LookupFailed : RecallCheckResult()
}
