package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [BodyweightLog]. */
@Dao
interface BodyweightLogDao {
    @Insert
    suspend fun insert(log: BodyweightLog): Long

    @Query("SELECT * FROM bodyweight_logs ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<BodyweightLog>

    /**
     * MASS panel + drilldown (ticket 16): every reading within [fromMs, toMs), ASCENDING - the
     * chart's own bucketing source ([com.kevin.legion.ui.bucketBodyweightDaily] groups these by
     * local day) and, reversed, the drilldown's history list. No schema change - a plain range
     * query alongside the existing unbounded [getRecent]/[mostRecent], same shape as
     * [MealLogDao.forWindow]/[SleepLogDao.forWindow].
     */
    @Query("SELECT * FROM bodyweight_logs WHERE loggedAt >= :fromMs AND loggedAt < :toMs ORDER BY loggedAt ASC")
    suspend fun forWindow(fromMs: Long, toMs: Long): List<BodyweightLog>

    @Query("SELECT * FROM bodyweight_logs ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): BodyweightLog?

    @Query("DELETE FROM bodyweight_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
