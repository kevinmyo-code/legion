package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [MealLog]. */
@Dao
interface MealLogDao {
    /** Returns the new row's id, so a same-turn `undo_last_log` (ticket 11 D36) can name it. */
    @Insert
    suspend fun insert(log: MealLog): Long

    @Query("SELECT * FROM meal_logs ORDER BY loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<MealLog>

    /**
     * Every meal logged within [fromMs, toMs) - the window
     * [com.kevin.legion.meals.MealController.dayGap] sums to compute today's actual macros
     * (D26). An EMPTY result for the day is the D27 "not logged" signal - the caller must check
     * `isEmpty()` before treating a sum as real, never assume a missing day summed to zero.
     */
    @Query("SELECT * FROM meal_logs WHERE loggedAt >= :fromMs AND loggedAt < :toMs")
    suspend fun forWindow(fromMs: Long, toMs: Long): List<MealLog>

    @Query("SELECT * FROM meal_logs ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): MealLog?

    @Query("DELETE FROM meal_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
