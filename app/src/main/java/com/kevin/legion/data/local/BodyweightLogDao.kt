package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [BodyweightLog]. */
@Dao
interface BodyweightLogDao {
    @Insert
    suspend fun insert(log: BodyweightLog): Long

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write, and
     * [com.kevin.legion.backend.BodyWriteThrough]'s local soft-delete. */
    @Update
    suspend fun update(log: BodyweightLog)

    /** Every row, active AND soft-deleted (body-supabase ticket) - [BodySync.pull]'s own local
     * match scan, same "getAll(), not getAllActive()" reasoning [EventsSync.pull]'s class doc
     * gives: a server tombstone must be able to find an already-deleted local row rather than
     * looking like "no local match" and getting wrongly resurrected. */
    @Query("SELECT * FROM bodyweight_logs")
    suspend fun getAll(): List<BodyweightLog>

    /** `deleted = 0` added body-supabase ticket, matching [EventDao]'s own active-read convention -
     * a tombstoned row (soft-deleted locally by a synced remote delete, or by
     * [com.kevin.legion.backend.BodyWriteThrough] on a configured install) must not render as if
     * it still existed. */
    @Query("SELECT * FROM bodyweight_logs WHERE deleted = 0 ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<BodyweightLog>

    /**
     * MASS panel + drilldown (ticket 16): every reading within [fromMs, toMs), ASCENDING - the
     * chart's own bucketing source ([com.kevin.legion.ui.bucketBodyweightDaily] groups these by
     * local day) and, reversed, the drilldown's history list. No schema change - a plain range
     * query alongside the existing unbounded [getRecent]/[mostRecent], same shape as
     * [MealLogDao.forWindow]/[SleepLogDao.forWindow].
     */
    @Query("SELECT * FROM bodyweight_logs WHERE deleted = 0 AND loggedAt >= :fromMs AND loggedAt < :toMs ORDER BY loggedAt ASC")
    suspend fun forWindow(fromMs: Long, toMs: Long): List<BodyweightLog>

    @Query("SELECT * FROM bodyweight_logs WHERE deleted = 0 ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): BodyweightLog?

    @Query("DELETE FROM bodyweight_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
