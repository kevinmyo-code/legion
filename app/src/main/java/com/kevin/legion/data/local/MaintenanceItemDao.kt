package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [MaintenanceItem].
 */
@Dao
interface MaintenanceItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStamped(item: MaintenanceItem)

    /** Stamps updatedAt for cross-device sync LWW (S1), then upserts (see VehicleDao.upsert). */
    suspend fun upsert(item: MaintenanceItem) =
        upsertStamped(item.copy(updatedAt = System.currentTimeMillis()))

    // Seed insert: fresh rows already carry a construction-time updatedAt, and
    // IGNORE means an existing (already-stamped) row is left untouched.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MaintenanceItem>)

    @Query("SELECT * FROM maintenance_items WHERE vehicleId = :vehicleId")
    suspend fun getForVehicle(vehicleId: String): List<MaintenanceItem>

    @Query("SELECT * FROM maintenance_items WHERE vehicleId = :vehicleId AND serviceName = :serviceName")
    suspend fun get(vehicleId: String, serviceName: String): MaintenanceItem?
}
