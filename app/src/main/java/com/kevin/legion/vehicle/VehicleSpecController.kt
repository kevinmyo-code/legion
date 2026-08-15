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
     * Decodes [vin] and saves the factory-spec fields for the active car, keeping
     * any manual paint/notes already entered. Returns true if specs were decoded
     * and saved. (Decode is vPIC; partial/empty on some imports.) [vehicleId]
     * override (ticket 01) - null means the active car. In practice always
     * null today: `lookup_vin` reads the OBD port directly (a live-hardware
     * read, the same physical-reality constraint as CLAUDE.md-ticket-01 §0's
     * "category A" tools), so it is never called with a named car - see
     * `LiveToolbox.lookupVin`'s doc for why that tool takes no `vehicle`
     * argument despite ticket 01's own §3 build spec listing it under
     * "category B".
     */
    suspend fun refreshFromVin(context: Context, vin: String, vehicleId: String? = null): Boolean {
        val specs = VinDecoder.decodeSpecs(vin) ?: return false
        val id = VehicleController.vehicleFor(context, vehicleId).obdMac
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
        return true
    }

    /** Reads the VIN off the OBD adapter and refreshes specs from it. */
    suspend fun refreshFromObd(context: Context): Boolean {
        val vin = ObdBluetoothManager.takeIf { it.isConnected }?.getVin() ?: return false
        return refreshFromVin(context, vin)
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
     * Live recall lookup for the active car (year/make/model). Empty if
     * none/unknown. [vehicleId] is the fleet-wide-voice override (ticket 01,
     * "category B" stored-data tool) - null means the active car, unchanged.
     */
    suspend fun recalls(context: Context, vehicleId: String? = null): List<VinDecoder.Recall> {
        val v = VehicleController.vehicleFor(context, vehicleId)
        return VinDecoder.fetchRecalls(v.year, v.make, v.model)
    }
}
