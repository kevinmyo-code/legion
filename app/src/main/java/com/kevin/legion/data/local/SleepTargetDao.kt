package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [SleepTarget]. Mirrors [MealTargetDao]'s shape exactly. */
@Dao
interface SleepTargetDao {
    /** REPLACE on the unique `effectiveFromDateEpoch` index, same shape as [MealTargetDao.upsert]/[BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: SleepTarget)

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write. Body-supabase
     * ticket; see [MealTargetDao.update]'s own doc comment for why nothing else calls this. */
    @Update
    suspend fun update(target: SleepTarget)

    /** Every row, active AND soft-deleted (body-supabase ticket) - see
     * [BodyweightLogDao.getAll]'s own doc comment for why. */
    @Query("SELECT * FROM sleep_targets")
    suspend fun getAll(): List<SleepTarget>

    /** The EXACT row already effective from [dateStartMs] - see [MealTargetDao.getByEffectiveDate]'s
     * own doc comment for why [com.kevin.legion.sleep.SleepController.setTarget] reuses this row's
     * guid on a same-day re-set rather than minting a fresh one. */
    @Query("SELECT * FROM sleep_targets WHERE effectiveFromDateEpoch = :dateStartMs LIMIT 1")
    suspend fun getByEffectiveDate(dateStartMs: Long): SleepTarget?

    /** The target effective as of [dateStartMs] - the latest row on or before it. `deleted = 0`
     * added body-supabase ticket, same reasoning as [MealTargetDao.currentTarget]. */
    @Query(
        "SELECT * FROM sleep_targets WHERE deleted = 0 AND effectiveFromDateEpoch = (" +
            "SELECT MAX(effectiveFromDateEpoch) FROM sleep_targets WHERE deleted = 0 AND effectiveFromDateEpoch <= :dateStartMs" +
            ")"
    )
    suspend fun currentTarget(dateStartMs: Long): SleepTarget?
}
