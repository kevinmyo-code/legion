package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CodeEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CodeEvent)

    @Query("SELECT * FROM code_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC")
    suspend fun getAll(vehicleId: String): List<CodeEvent>

    @Query("SELECT * FROM code_events WHERE vehicleId = :vehicleId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(vehicleId: String): CodeEvent?

    /** All events in a time range, ordered oldest-first. */
    @Query(
        "SELECT * FROM code_events WHERE vehicleId = :vehicleId " +
            "AND timestamp >= :fromMs AND timestamp <= :toMs ORDER BY timestamp ASC"
    )
    suspend fun getInRange(vehicleId: String, fromMs: Long, toMs: Long): List<CodeEvent>

    /** Count of code events in a time range - feeds MonthlyRecapController's aggregation. */
    @Query(
        "SELECT COUNT(*) FROM code_events WHERE vehicleId = :vehicleId " +
            "AND timestamp >= :fromMs AND timestamp <= :toMs"
    )
    suspend fun countInRange(vehicleId: String, fromMs: Long, toMs: Long): Int
}
