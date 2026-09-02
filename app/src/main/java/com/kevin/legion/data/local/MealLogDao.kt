package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [MealLog]. */
@Dao
interface MealLogDao {
    /** Returns the new row's id, so a same-turn `undo_last_log` (ticket 11 D36) can name it. */
    @Insert
    suspend fun insert(log: MealLog): Long

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write, and
     * [com.kevin.legion.backend.BodyWriteThrough]'s local soft-delete. */
    @Update
    suspend fun update(log: MealLog)

    /** Every row, active AND soft-deleted (body-supabase ticket) - see [BodyweightLogDao.getAll]'s
     * own doc comment for why. */
    @Query("SELECT * FROM meal_logs")
    suspend fun getAll(): List<MealLog>

    /** `deleted = 0` added body-supabase ticket - see [BodyweightLogDao.getRecent]'s own doc. */
    @Query("SELECT * FROM meal_logs WHERE deleted = 0 ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MealLog>

    /**
     * Every meal logged within [fromMs, toMs) - the window
     * [com.kevin.legion.meals.MealController.dayGap] sums to compute today's actual macros
     * (D26). An EMPTY result for the day is the D27 "not logged" signal - the caller must check
     * `isEmpty()` before treating a sum as real, never assume a missing day summed to zero.
     */
    @Query("SELECT * FROM meal_logs WHERE deleted = 0 AND loggedAt >= :fromMs AND loggedAt < :toMs")
    suspend fun forWindow(fromMs: Long, toMs: Long): List<MealLog>

    @Query("SELECT * FROM meal_logs WHERE deleted = 0 ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): MealLog?

    @Query("DELETE FROM meal_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
