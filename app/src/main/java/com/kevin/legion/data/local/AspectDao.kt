package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [Aspect]. Deliberately thin - [com.kevin.legion.engine.RecordStore] is
 * the write door for RECORDS, but an aspect's own lifecycle (create/rename/archive/restore) has
 * no reference-policy or computed-field concerns, so it is not forced through that door too. */
@Dao
interface AspectDao {
    @Insert
    suspend fun insert(aspect: Aspect): Long

    @Update
    suspend fun update(aspect: Aspect)

    @Query("SELECT * FROM aspects WHERE id = :id")
    suspend fun getById(id: Long): Aspect?

    @Query("SELECT * FROM aspects WHERE archived = 0 ORDER BY position ASC")
    suspend fun listActive(): List<Aspect>

    @Query("SELECT * FROM aspects WHERE archived = 1 ORDER BY archivedAt DESC")
    suspend fun listArchived(): List<Aspect>

    @Query("UPDATE aspects SET archived = 1, archivedAt = :now, updatedAt = :now WHERE id = :id")
    suspend fun archive(id: Long, now: Long)

    @Query("UPDATE aspects SET archived = 0, archivedAt = NULL, updatedAt = :now WHERE id = :id")
    suspend fun restore(id: Long, now: Long)

    /** The 30-day hard purge (ticket 03 answer point 4) - only ever reaches an aspect already
     * archived at least 30 days, matching [EngineRecordDao.purgeDeletedBefore]'s record-level twin. */
    @Query("DELETE FROM aspects WHERE archived = 1 AND archivedAt IS NOT NULL AND archivedAt < :cutoff")
    suspend fun purgeArchivedBefore(cutoff: Long): Int
}
