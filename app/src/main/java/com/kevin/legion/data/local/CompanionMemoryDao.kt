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

    /**
     * The recall scan window: **everything the companion knows about the DRIVER, whichever car is
     * connected**, plus the car facts belonging to the car that IS connected.
     *
     * Replaces [getRecent] on the recall path, 2026-08-20. `companion_memories` is keyed by
     * `vehicleId` because it was written for a car launcher, so recall only ever read the active
     * car's slice - and on Kevin's own device that stranded **46 memories about him** the moment
     * the Jeep was the active car. Not car facts: his music taste, his work address, what he
     * thought of an album. Which car happened to be connected decided what his companion
     * remembered about him.
     *
     * `car_anchored` stays scoped, because a service record genuinely belongs to one car and
     * surfacing the Outlander's oil change while he is in the Jeep would be a different bug.
     * `driver` and `relationship` are about the person, and the person does not change car to car.
     *
     * No schema change: the partition was only ever in this WHERE clause.
     */
    @Query(
        "SELECT * FROM companion_memories " +
            "WHERE category != 'car_anchored' OR vehicleId = :vehicleId " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun getRecallScan(vehicleId: String, limit: Int): List<CompanionMemory>

    /** All memories of one [CompanionMemory.Source] for a car - reflection's input pool (ticket 05). */
    @Query("SELECT * FROM companion_memories WHERE vehicleId = :vehicleId AND source = :source ORDER BY createdAt DESC")
    suspend fun bySource(vehicleId: String, source: String): List<CompanionMemory>

    /**
     * Every consolidated/reflected memory, newest first, across ALL cars - the read behind the
     * driver-facing memory screen (2026-08-18). Deliberately not scoped to the active car the way
     * [getRecent] is: the screen exists so the driver can find and delete a wrong memory, and a
     * memory attached to a car he is not sitting in is exactly the one he would otherwise never
     * see.
     */
    @Query("SELECT * FROM companion_memories ORDER BY createdAt DESC LIMIT :limit")
    suspend fun allRecent(limit: Int): List<CompanionMemory>

    /** Deletes one memory the driver rejected. Nothing rewrites it: the consolidation pass reads
     * [EpisodicTurn]s, which are cleared once consolidated, so a deleted row does not come back. */
    @Query("DELETE FROM companion_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

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
