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
}
