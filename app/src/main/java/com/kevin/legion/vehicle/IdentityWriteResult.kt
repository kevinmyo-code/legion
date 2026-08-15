package com.kevin.legion.vehicle

/**
 * One field where a stored [com.kevin.legion.data.local.Vehicle]'s value and a vPIC VIN decode
 * disagree. [VehicleController.applyDecodedIdentity] never writes over a disagreement - this is
 * what it reports instead, so a caller can say in words what it found rather than guessing at a
 * bare failure. [field] is one of "year"/"make"/"model"/"trim".
 */
data class FieldConflict(val field: String, val onFile: String, val decoded: String)

/**
 * Outcome of [VehicleController.applyDecodedIdentity] - ticket 04's VIN identity write-back
 * (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`,
 * `13-the-jeep-row-lost-its-identity.md`). See that function's doc for the fill-blanks,
 * never-silently-overwrite policy this reports on. A bare Boolean cannot say which of these four
 * genuinely different outcomes happened, which is exactly the "false success" shape
 * `lessons.md` L16 exists to keep out of this codebase.
 */
sealed class IdentityWriteResult {
    /**
     * At least one blank field was filled in from the decode. [changedFields] names which
     * ("year"/"make"/"model"/"trim") - a field not named here either already agreed with the
     * decode, or the decode had nothing to say about it (e.g. no trim on the VIN).
     */
    data class Applied(val changedFields: List<String>) : IdentityWriteResult()

    /**
     * Every field the decode could speak to already matched what's on file (or the row already
     * had everything the decode could offer). Nothing was written - no SQL call was even made,
     * so [com.kevin.legion.data.local.Vehicle.updatedAt] was not re-stamped for nothing, the same
     * discipline [VehicleController.correctVehicle]'s own no-op branch follows.
     */
    object NothingToDo : IdentityWriteResult()

    /**
     * The decode disagreed with at least one driver-entered field. **Nothing was written for ANY
     * field, not only the disagreeing ones** - see [VehicleController.applyDecodedIdentity]'s doc
     * for why a half-applied identity (some fields decoded-and-written, others left silently
     * disagreeing) is a worse, more confusing state than an unapplied one, not a smaller version
     * of the same problem. [fields] carries every disagreement found, so a caller can show all of
     * them at once rather than one at a time.
     */
    data class Conflict(val fields: List<FieldConflict>) : IdentityWriteResult()

    /**
     * The decode itself had nothing usable to offer (no make + model together) - see
     * [VinDecoder.DecodedVin.isUsable]. In practice this only reaches
     * [VehicleController.applyDecodedIdentity] via a null decode, since
     * [VinDecoder]'s own parse already discards an unusable result to null before a caller ever
     * sees it - kept as an explicit outcome anyway so the function is defensive against being
     * called directly with a hand-built, not-actually-usable [VinDecoder.DecodedVin].
     */
    object Unusable : IdentityWriteResult()

    /**
     * No `vehicles` row exists for the vehicle id at all - there is nothing to write the identity
     * onto. Also returned if the row existed at read time but vanished before the write (the
     * same race [VehicleDao.applyDecodedIdentity]'s doc names): the caller is told honestly
     * rather than shown a false success for a write that touched zero rows.
     */
    object NoSuchVehicle : IdentityWriteResult()
}
