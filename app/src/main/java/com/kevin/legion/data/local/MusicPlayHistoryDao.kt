package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * One row per (title, artist) with how many times [MusicPlayHistoryDao.getMostPlayed] saw it -
 * the raw input to `browse_my_music`'s `legion_history` "favourite" inference
 * (`service/LiveToolbox.kt`). A play count is not a favourite by itself; the caller that turns
 * this into words is the one responsible for saying "LEGION's own count", per
 * [MusicPlayHistoryEntry]'s own doc comment on why that distinction is load-bearing.
 */
data class PlayCountRow(val title: String, val artist: String, val playCount: Int)

@Dao
interface MusicPlayHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MusicPlayHistoryEntry): Long

    /** Newest first, capped at [limit] - what `browse_my_music`'s `legion_history` source reads. */
    @Query("SELECT * FROM music_play_history ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MusicPlayHistoryEntry>

    /**
     * Grouped play counts, most-observed first. Room maps this directly onto [PlayCountRow] by
     * column name (no `@Embedded`/manual mapping needed - the projected column names match the
     * data class fields exactly).
     */
    @Query(
        "SELECT title, artist, COUNT(*) as playCount FROM music_play_history " +
            "GROUP BY title, artist ORDER BY playCount DESC LIMIT :limit"
    )
    suspend fun getMostPlayed(limit: Int): List<PlayCountRow>
}
