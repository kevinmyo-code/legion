package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * No update, no delete - a finalised [Drive] is an append-only fact, same posture as
 * [CodeClearEventDao]/[OdbSampleDao]'s sync-relevant tables. See [Drive]'s own doc comment.
 */
@Dao
interface DriveDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(drive: Drive): Long

    /** Newest first, capped at [limit] - what a future post-drive-summary or history surface reads. */
    @Query("SELECT * FROM drives WHERE vehicleId = :vehicleId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(vehicleId: String, limit: Int): List<Drive>
}
