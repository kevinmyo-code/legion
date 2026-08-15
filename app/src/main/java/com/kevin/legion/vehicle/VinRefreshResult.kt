package com.kevin.legion.vehicle

/**
 * Outcome of [VehicleSpecController.refreshFromVin] / [VehicleSpecController.refreshFromObd] /
 * [VehicleSpecController.reconcileIdentityFromStoredVin] - ticket 04's VIN identity write-back
 * (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`,
 * `13-the-jeep-row-lost-its-identity.md`).
 *
 * A vPIC lookup actually does two separate writes - the `vehicle_specs` factory-spec row, and (new
 * with this ticket) the `vehicles` identity columns - and this reports both honestly rather than
 * collapsing them into one bare success flag. CLAUDE.md §7: network calls degrade gracefully
 * offline, which means every branch here has to be sayable in words, never a spinner that just
 * stops.
 */
sealed class VinRefreshResult {
    /**
     * The vPIC call succeeded (a network response came back and was parseable). [specsSaved] is
     * true unless the response had no `Results` element at all to build a spec row from - a
     * malformed/offline call never reaches this branch at all, see [DecodeFailed]. [identity] is
     * what happened, if anything, to the car's `vehicles` row - see [IdentityWriteResult].
     */
    data class Decoded(val specsSaved: Boolean, val identity: IdentityWriteResult) : VinRefreshResult()

    /**
     * vPIC could not be reached (offline, timeout), the VIN was malformed (not 17 characters), or
     * the request came back with nothing at all to parse. Nothing was written anywhere - neither
     * the spec row nor the vehicle identity.
     */
    object DecodeFailed : VinRefreshResult()

    /**
     * [VehicleSpecController.reconcileIdentityFromStoredVin] only: `vehicle_specs` has no VIN on
     * file to re-decode. Reported distinctly from [DecodeFailed] - one is "nothing to try", the
     * other is "tried, and it didn't work" - so the driver is told which one actually happened.
     */
    object NoStoredVin : VinRefreshResult()
}
