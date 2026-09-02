package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [SleepLog]. Mirrors [MealLogDao]'s shape exactly. */
@Dao
interface SleepLogDao {
    /** Returns the new row's id, so a same-turn `undo_last_log` (ticket 11 D36, extended to sleep 2026-08-07) can name it. */
    @Insert
    suspend fun insert(log: SleepLog): Long

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write, and
     * [com.kevin.legion.backend.BodyWriteThrough]'s local soft-delete. */
    @Update
    suspend fun update(log: SleepLog)

    /** Every row, active AND soft-deleted (body-supabase ticket) - see [BodyweightLogDao.getAll]'s
     * own doc comment for why. */
    @Query("SELECT * FROM sleep_logs")
    suspend fun getAll(): List<SleepLog>

    /** `deleted = 0` added body-supabase ticket - see [BodyweightLogDao.getRecent]'s own doc. */
    @Query("SELECT * FROM sleep_logs WHERE deleted = 0 ORDER BY sleepDate DESC, loggedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<SleepLog>

    /**
     * Every night logged within [fromMs, toMs) by [SleepLog.sleepDate] (the wake-date convention -
     * see that field's doc comment) - the window [com.kevin.legion.sleep.SleepController.gapFor]
     * sums to compute a day's actual sleep. An EMPTY result is the "not logged" signal, matching
     * [MealLogDao.forWindow]'s own doc comment - the caller must check `isEmpty()` before treating
     * a sum as real, never assume a missing night summed to zero.
     */
    @Query("SELECT * FROM sleep_logs WHERE deleted = 0 AND sleepDate >= :fromMs AND sleepDate < :toMs")
    suspend fun forWindow(fromMs: Long, toMs: Long): List<SleepLog>

    @Query("SELECT * FROM sleep_logs WHERE deleted = 0 ORDER BY loggedAt DESC, id DESC LIMIT 1")
    suspend fun mostRecent(): SleepLog?

    @Query("DELETE FROM sleep_logs WHERE id = :id")
    suspend fun deleteById(id: Long)
}
