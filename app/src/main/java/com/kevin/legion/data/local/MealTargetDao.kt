package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [MealTarget]. */
@Dao
interface MealTargetDao {
    /** REPLACE on the unique `effectiveFromDateEpoch` index, same shape as [BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: MealTarget)

    /** The target effective as of [dateStartMs] - the latest row on or before it. */
    @Query(
        "SELECT * FROM meal_targets WHERE effectiveFromDateEpoch = (" +
            "SELECT MAX(effectiveFromDateEpoch) FROM meal_targets WHERE effectiveFromDateEpoch <= :dateStartMs" +
            ")"
    )
    suspend fun currentTarget(dateStartMs: Long): MealTarget?
}
