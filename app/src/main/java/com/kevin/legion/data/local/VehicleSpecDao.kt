package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [VehicleSpec] - the per-car build-details encyclopedia. */
@Dao
interface VehicleSpecDao {
    @Query("SELECT * FROM vehicle_specs WHERE vehicleId = :vehicleId")
    suspend fun get(vehicleId: String): VehicleSpec?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStamped(spec: VehicleSpec)

    /** Stamps updatedAt for cross-device sync LWW (S1), then upserts (see VehicleDao.upsert). */
    suspend fun upsert(spec: VehicleSpec) =
        upsertStamped(spec.copy(updatedAt = System.currentTimeMillis()))

    /** Every vehicle_spec row on this device, across all vehicles - what
     * [com.kevin.legion.backend.FleetReconcile] reads as the upload source, mirroring
     * `ChassisQuirkDao.getAll`'s "every row, no vehicle filter" role for a household-wide table.
     * `vehicle_specs` is per-vehicle rather than household-shared, but the caller needs every row
     * regardless of which car it belongs to, same as [BuildEntryDao.getAllForUpload]. */
    @Query("SELECT * FROM vehicle_specs")
    suspend fun getAll(): List<VehicleSpec>
}
