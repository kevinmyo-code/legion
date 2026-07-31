package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DailyDriveLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: DailyDriveLog)

    @Delete
    suspend fun delete(log: DailyDriveLog)

    @Query(
        "SELECT * FROM daily_drive_logs WHERE vehicleId = :vehicleId " +
            "AND year = :year AND month = :month AND day = :day LIMIT 1"
    )
    suspend fun getForDay(vehicleId: String, year: Int, month: Int, day: Int): DailyDriveLog?

    /** Newest first, capped - the daily shelf only needs a recent window, unlike the monthly/yearly archives. */
    @Query("SELECT * FROM daily_drive_logs WHERE vehicleId = :vehicleId ORDER BY year DESC, month DESC, day DESC LIMIT :limit")
    suspend fun getRecent(vehicleId: String, limit: Int): List<DailyDriveLog>
}
