package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [WorkoutPlan] - the weekly session-count half of the plan. */
@Dao
interface WorkoutPlanDao {
    /** REPLACE on the unique `effectiveFromWeekEpoch` index, same reasoning as [BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: WorkoutPlan)

    /** The plan effective as of [weekStartMs] - the latest row on or before it, i.e. "copy forward". */
    @Query(
        "SELECT * FROM workout_plans WHERE effectiveFromWeekEpoch = (" +
            "SELECT MAX(effectiveFromWeekEpoch) FROM workout_plans WHERE effectiveFromWeekEpoch <= :weekStartMs" +
            ")"
    )
    suspend fun currentPlan(weekStartMs: Long): WorkoutPlan?
}
