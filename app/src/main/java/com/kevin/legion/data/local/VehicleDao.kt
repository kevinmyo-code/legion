package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for [Vehicle].
 */
@Dao
interface VehicleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStamped(vehicle: Vehicle)

    /**
     * Every vehicle write goes through here so [Vehicle.updatedAt] is refreshed for
     * cross-device sync last-write-wins (S1) - a copy loaded-then-edited would keep
     * its old stamp otherwise, and LWW couldn't tell which device is newer.
     */
    suspend fun upsert(vehicle: Vehicle) =
        upsertStamped(vehicle.copy(updatedAt = System.currentTimeMillis()))

    @Query("SELECT * FROM vehicles WHERE obdMac = :mac")
    suspend fun getByMac(mac: String): Vehicle?

    /** Active cars only - archived ones are hidden from the roster and picker. */
    @Query("SELECT * FROM vehicles WHERE archived = 0")
    suspend fun getAll(): List<Vehicle>

    /** Every car including archived, for the roster's "Show archived" toggle. */
    @Query("SELECT * FROM vehicles")
    suspend fun getAllIncludingArchived(): List<Vehicle>
}
