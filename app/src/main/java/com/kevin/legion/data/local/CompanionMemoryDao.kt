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
     * Every row of [category] whose text starts with [prefix], oldest first - the goal-plans
     * ticket 03 read behind "having said once that he has no gym, he should not have to say it
     * again." A plan revision needs exactly the SUBSET of `driver`-category rows that are stated
     * fitness constraints, not every driver fact this category also holds (music taste, a work
     * address, a nickname) - [prefix] is that subset marker
     * ([com.kevin.legion.advisor.GoalPlanAgent.CONSTRAINT_PREFIX], the only value in practice),
     * applied by the writer rather than a second category, because a stated constraint is still
     * exactly the kind of durable, cross-vehicle fact about the person [Category.DRIVER] already
     * means - see [CompanionMemoryRecallScopeTest] for why that category is never scoped to
     * whichever car happens to be connected. No new table, no new category: only a text
     * convention on top of a column this DAO already has.
     *
     * Oldest first (unlike [getRecallScan]/[getRecent]'s newest-first) because a caller folding
     * these into a prompt wants them in the order they were actually said, not reverse-
     * chronological - a plan reads more sensibly built up in the sequence a person stated things.
     */
    @Query("SELECT * FROM companion_memories WHERE category = :category AND text LIKE :prefix || '%' ORDER BY createdAt ASC")
    suspend fun byCategoryPrefixed(category: String, prefix: String): List<CompanionMemory>

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
