package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data Access Object for [MemoryEntry].
 */
@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: MemoryEntry)

    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryEntry>

    /** Case-insensitive exact match, so "remember X" twice doesn't duplicate a row. */
    @Query("SELECT * FROM memories WHERE LOWER(text) = LOWER(:text) LIMIT 1")
    suspend fun findByText(text: String): MemoryEntry?

    /** Refreshes a memory's recency (used when the driver re-mentions it) without duplicating it. */
    @Query("UPDATE memories SET timestamp = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    /** Clears every memory (the "forget everything" reset). */
    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
