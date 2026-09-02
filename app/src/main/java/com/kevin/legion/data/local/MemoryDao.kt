package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * Data Access Object for [MemoryEntry].
 */
@Dao
interface MemoryDao {
    @Insert
    suspend fun insert(memory: MemoryEntry): Long

    /** Whole-row update - [com.kevin.legion.backend.MemorySync.pull]'s merge write, and
     * [com.kevin.legion.backend.MemoryWriteThrough]'s local soft-delete. */
    @Update
    suspend fun update(memory: MemoryEntry)

    /** Every row, active AND soft-deleted (memory-supabase ticket) - [com.kevin.legion.backend.MemorySync.pull]'s
     * own local match scan, same "getAll(), not getAllActive()" reasoning [BodyMerge]'s own class
     * doc gives: a server tombstone must be able to find an already-deleted local row rather than
     * looking like "no local match" and getting wrongly resurrected. */
    @Query("SELECT * FROM memories")
    suspend fun getAll(): List<MemoryEntry>

    /** `deleted = 0` added memory-supabase ticket, matching [BodyweightLogDao]'s own active-read
     * convention - a tombstoned row (soft-deleted locally by a synced remote delete, or by
     * [com.kevin.legion.backend.MemoryWriteThrough] on a configured install) must not render, or be
     * recalled into a prompt, as if it still existed. */
    @Query("SELECT * FROM memories WHERE deleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MemoryEntry>

    /** Case-insensitive exact match, so "remember X" twice doesn't duplicate a row. */
    @Query("SELECT * FROM memories WHERE deleted = 0 AND LOWER(text) = LOWER(:text) LIMIT 1")
    suspend fun findByText(text: String): MemoryEntry?

    /** Refreshes a memory's recency (used when the driver re-mentions it) without duplicating it. */
    @Query("UPDATE memories SET timestamp = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    /** Hard delete - the unconfigured-install fast path and the "cancel a still-pending create"
     * path in [com.kevin.legion.backend.MemoryWriteThrough]. A genuinely synced row is soft-deleted
     * instead, via [update], so its tombstone can reach the server. */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Clears every memory (the "forget everything" reset) - local-only; see
     * [com.kevin.legion.backend.MemoryWriteThrough.deleteAllMemoryEntries] for the configured-install
     * path that tombstones and pushes each row before this ever runs. */
    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
