package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [SleepTarget]. Mirrors [MealTargetDao]'s shape exactly. */
@Dao
interface SleepTargetDao {
    /** REPLACE on the unique `effectiveFromDateEpoch` index, same shape as [MealTargetDao.upsert]/[BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: SleepTarget)

    /** The target effective as of [dateStartMs] - the latest row on or before it. */
    @Query(
        "SELECT * FROM sleep_targets WHERE effectiveFromDateEpoch = (" +
            "SELECT MAX(effectiveFromDateEpoch) FROM sleep_targets WHERE effectiveFromDateEpoch <= :dateStartMs" +
            ")"
    )
    suspend fun currentTarget(dateStartMs: Long): SleepTarget?
}
