package com.kevin.midnightai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface DriveDao {
    @Insert
    suspend fun insert(drive: Drive): Long

    /** Fleet upload: drives past the last row the engine acknowledged, oldest first. */
    @Query("SELECT * FROM drives WHERE vehicleId = :vehicleId AND id > :afterId ORDER BY id ASC LIMIT :limit")
    suspend fun pendingAfter(vehicleId: String, afterId: Long, limit: Int): List<Drive>

    @Query("SELECT COUNT(*) FROM drives WHERE vehicleId = :vehicleId AND id > :afterId")
    suspend fun countAfter(vehicleId: String, afterId: Long): Int
}
