package com.kevin.legion.vehicle

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VehicleSpec

/**
 * Owns the build-details encyclopedia ([VehicleSpec]) for the active car: reads
 * the stored row, refreshes the VIN-decoded fields ([VinDecoder]) while
 * preserving the driver's manual entries (paint/notes), saves manual edits, and
 * fetches recalls live on request.
 */
object VehicleSpecController {

    /**
     * The stored encyclopedia row for the active car, or null if none yet.
     * [vehicleId] is the fleet-wide-voice override (ticket 01, "category B"
     * stored-data tool) - null means the active car, unchanged.
     */
    suspend fun current(context: Context, vehicleId: String? = null): VehicleSpec? {
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
        return CarDatabase.getDatabase(context).vehicleSpecDao().get(id)
    }

    /**
     * Decodes [vin] via vPIC, saves the factory-spec fields for the active car (keeping any
     * manual paint/notes already entered), and - **ticket 04's write-back**
     * (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`,
     * `13-the-jeep-row-lost-its-identity.md`) - applies the SAME decode's identity fields
     * (year/make/model/trim) onto the `vehicles` row via
     * [VehicleController.applyDecodedIdentity]. Before this, the two halves of one vPIC response
     * were pulled apart: the spec row got written, the identity fields were silently discarded,
     * and Kevin's `vehicle_specs` held a fully-decoded Jeep VIN for three weeks while `vehicles`
     * still read blank.
     *
     * Returns a [VinRefreshResult] carrying BOTH outcomes - whether the spec row was saved, and
     * what happened (if anything) to the identity - rather than one bare success flag; see that
     * type's doc.
     *
     * [vehicleId] override (ticket 01) - null means the active car. In practice always null
     * today: `lookup_vin` reads the OBD port directly (a live-hardware read, the same
     * physical-reality constraint as CLAUDE.md-ticket-01 §0's "category A" tools), so it is
     * never called with a named car - see `LiveToolbox.lookupVin`'s doc for why that tool takes
     * no `vehicle` argument despite ticket 01's own §3 build spec listing it under "category B".
     */
    suspend fun refreshFromVin(context: Context, vin: String, vehicleId: String? = null): VinRefreshResult {
        val all = VinDecoder.decodeAll(vin) ?: return VinRefreshResult.DecodeFailed
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac

        var specsSaved = false
        all.specs?.let { specs ->
            val dao = CarDatabase.getDatabase(context).vehicleSpecDao()
            val existing = dao.get(id)
            dao.upsert(
                VehicleSpec(
                    vehicleId = id,
                    vin = specs.vin,
                    engineCylinders = specs.engineCylinders,
                    displacementL = specs.displacementL,
                    engineHp = specs.engineHp,
                    engineConfig = specs.engineConfig,
                    fuelType = specs.fuelType,
                    transmissionStyle = specs.transmissionStyle,
                    transmissionSpeeds = specs.transmissionSpeeds,
                    driveType = specs.driveType,
                    bodyClass = specs.bodyClass,
                    doors = specs.doors,
                    series = specs.series,
                    vehicleType = specs.vehicleType,
                    manufacturer = specs.manufacturer,
                    plantCity = specs.plantCity,
                    plantCountry = specs.plantCountry,
                    // Preserve driver-entered fields across a re-decode.
                    paintColor = existing?.paintColor ?: "",
                    paintCode = existing?.paintCode ?: "",
                    buildNotes = existing?.buildNotes ?: "",
                    decodedAt = System.currentTimeMillis(),
                )
            )
            specsSaved = true
        }

        val identity = VehicleController.applyDecodedIdentity(context, id, all.decoded)
        return VinRefreshResult.Decoded(specsSaved, identity)
    }

    /** Reads the VIN off the OBD adapter and refreshes specs + identity from it. */
    suspend fun refreshFromObd(context: Context): VinRefreshResult {
        val vin = ObdBluetoothManager.takeIf { it.isConnected }?.getVin() ?: return VinRefreshResult.DecodeFailed
        return refreshFromVin(context, vin)
    }

    /**
     * The repair path (ticket 04): re-decodes the VIN **already stored in `vehicle_specs`** for
     * [vehicleId] (null = active car) and applies its identity, WITHOUT touching the OBD adapter
     * at all. `vehicle_specs.vin` has held a fully-decoded VIN since 2026-07-26 that
     * [refreshFromVin] was never called again to propagate - this lets Kevin fix that from the
     * data already on disk, without his adapter needing to be plugged in
     * ([refreshFromObd] requires a LIVE connection; this needs only network).
     *
     * Distinguishes "nothing to try" ([VinRefreshResult.NoStoredVin], no VIN on file at all) from
     * "tried, and it didn't work" ([VinRefreshResult.DecodeFailed]) - see that type's doc.
     */
    suspend fun reconcileIdentityFromStoredVin(context: Context, vehicleId: String? = null): VinRefreshResult {
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
        val storedVin = CarDatabase.getDatabase(context).vehicleSpecDao().get(id)?.vin?.trim().orEmpty()
        if (storedVin.isBlank()) return VinRefreshResult.NoStoredVin
        return refreshFromVin(context, storedVin, vehicleId)
    }

    /**
     * Saves the driver-entered fields, preserving the decoded ones (creates
     * the row if needed). [vehicleId] override (ticket 01) - null means the
     * active car; not currently exercised by any Live tool (this is a
     * settings-form write, not one of ticket 01's 16 stored-data tools),
     * added for the controller-threading consistency the ticket's §2 asks for.
     */
    suspend fun saveManual(context: Context, paintColor: String, paintCode: String, buildNotes: String, vehicleId: String? = null) {
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
        val dao = CarDatabase.getDatabase(context).vehicleSpecDao()
        val existing = dao.get(id) ?: VehicleSpec(vehicleId = id)
        dao.upsert(
            existing.copy(
                paintColor = paintColor.trim(),
                paintCode = paintCode.trim(),
                buildNotes = buildNotes.trim(),
            )
        )
    }

    /**
     * Live recall lookup for the active car (year/make/model, never by VIN - see
     * [RecallCheckResult]'s doc). Gates on [identityPresent] - **not**
     * [com.kevin.legion.data.local.Vehicle.confirmed]; ticket 12
     * (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`) found the voice tool and the
     * proactive startup push disagreeing about which flag gated this, and this is the single
     * place both now call through, so they cannot disagree again. [vehicleId] is the
     * fleet-wide-voice override (ticket 01, "category B" stored-data tool) - null means the
     * active car, unchanged.
     */
    suspend fun recalls(context: Context, vehicleId: String? = null): RecallCheckResult {
        val v = VehicleController.vehicleFor(context, vehicleId)
        val missing = missingIdentityFields(v)
        if (missing.isNotEmpty()) return RecallCheckResult.IdentityMissing(missing)
        val recalls = VinDecoder.fetchRecalls(v.year, v.make, v.model) ?: return RecallCheckResult.LookupFailed
        return RecallCheckResult.Checked(recalls)
    }
}
