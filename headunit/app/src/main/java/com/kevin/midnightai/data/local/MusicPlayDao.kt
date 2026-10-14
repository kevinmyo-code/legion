package com.kevin.midnightai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MusicPlayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(play: MusicPlay)

    /** All plays since a cutoff, newest first - the taste-summary aggregation window. */
    @Query(
        "SELECT * FROM music_plays " +
            "WHERE timestamp >= :sinceMs " +
            "ORDER BY timestamp DESC"
    )
    suspend fun getSince(sinceMs: Long): List<MusicPlay>

    /** Purge plays older than a cutoff to cap storage growth. */
    @Query("DELETE FROM music_plays WHERE timestamp < :beforeMs")
    suspend fun purgeOlderThan(beforeMs: Long)
}
