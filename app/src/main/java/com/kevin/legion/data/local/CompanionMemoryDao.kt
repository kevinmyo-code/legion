package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface CompanionMemoryDao {
    @Insert
    suspend fun insert(memory: CompanionMemory): Long

    /** Newest-first, for a car - ticket 03's scan window before ranking. */
    @Query("SELECT * FROM companion_memories WHERE vehicleId = :vehicleId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(vehicleId: String, limit: Int): List<CompanionMemory>

    /** All memories of one [CompanionMemory.Source] for a car - reflection's input pool (ticket 05). */
    @Query("SELECT * FROM companion_memories WHERE vehicleId = :vehicleId AND source = :source ORDER BY createdAt DESC")
    suspend fun bySource(vehicleId: String, source: String): List<CompanionMemory>

    /** Refreshes recency on recall (mirrors the old MemoryEntry.touch()) - ticket 03's decay input. */
    @Query("UPDATE companion_memories SET lastAccessedAt = :ts WHERE id = :id")
    suspend fun touch(id: Long, ts: Long)

    /** Clears every consolidated/reflected memory for a car (part of the "New companion" reset). */
    @Query("DELETE FROM companion_memories WHERE vehicleId = :vehicleId")
    suspend fun deleteForVehicle(vehicleId: String)

    /** Clears every consolidated/reflected memory, every car - the "Forget memories" reset. */
    @Query("DELETE FROM companion_memories")
    suspend fun deleteAll()

    /** Latest memory's creation time for a car - continuity (ticket 06), paired with EpisodicTurnDao.latestTimestamp. */
    @Query("SELECT MAX(createdAt) FROM companion_memories WHERE vehicleId = :vehicleId")
    suspend fun latestCreatedAt(vehicleId: String): Long?
}
