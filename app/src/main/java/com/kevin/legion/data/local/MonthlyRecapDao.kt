package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MonthlyRecapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recap: MonthlyRecap)

    @Delete
    suspend fun delete(recap: MonthlyRecap)

    @Query(
        "SELECT * FROM monthly_recaps WHERE vehicleId = :vehicleId " +
            "AND year = :year AND month = :month LIMIT 1"
    )
    suspend fun getForMonth(vehicleId: String, year: Int, month: Int): MonthlyRecap?

    @Query(
        "SELECT * FROM monthly_recaps WHERE vehicleId = :vehicleId " +
            "ORDER BY year DESC, month DESC LIMIT 1"
    )
    suspend fun getLatest(vehicleId: String): MonthlyRecap?

    /** All of a vehicle's recaps, newest first - for the future shelf/archive UI. */
    @Query("SELECT * FROM monthly_recaps WHERE vehicleId = :vehicleId ORDER BY year DESC, month DESC")
    suspend fun getAll(vehicleId: String): List<MonthlyRecap>
}
