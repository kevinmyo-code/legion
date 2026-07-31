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

    /** The stored encyclopedia row for the active car, or null if none yet. */
    suspend fun current(context: Context): VehicleSpec? {
        val id = VehicleController.currentVehicle(context).obdMac
        return CarDatabase.getDatabase(context).vehicleSpecDao().get(id)
    }

    /**
     * Decodes [vin] and saves the factory-spec fields for the active car, keeping
     * any manual paint/notes already entered. Returns true if specs were decoded
     * and saved. (Decode is vPIC; partial/empty on some imports.)
     */
    suspend fun refreshFromVin(context: Context, vin: String): Boolean {
        val specs = VinDecoder.decodeSpecs(vin) ?: return false
        val id = VehicleController.currentVehicle(context).obdMac
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

    /** Saves the driver-entered fields, preserving the decoded ones (creates the row if needed). */
    suspend fun saveManual(context: Context, paintColor: String, paintCode: String, buildNotes: String) {
        val id = VehicleController.currentVehicle(context).obdMac
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

    /** Live recall lookup for the active car (year/make/model). Empty if none/unknown. */
    suspend fun recalls(context: Context): List<VinDecoder.Recall> {
        val v = VehicleController.currentVehicle(context)
        return VinDecoder.fetchRecalls(v.year, v.make, v.model)
    }
}
