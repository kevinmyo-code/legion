package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Data Access Object for [WorkoutPlanItem] - the per-exercise target-sets half of the plan. */
@Dao
interface WorkoutPlanItemDao {
    /** REPLACE on the unique `(exercise, effectiveFromWeekEpoch)` index, same shape as [BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WorkoutPlanItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WorkoutPlanItem>)

    /**
     * Every exercise's currently-effective target as of [weekStartMs] - one row per exercise, the
     * latest whose [WorkoutPlanItem.effectiveFromWeekEpoch] is on or before the week being asked
     * about. Same "copy forward" read [BudgetTargetDao.currentTargets] performs.
     */
    @Query(
        "SELECT * FROM workout_plan_items wpi WHERE effectiveFromWeekEpoch = (" +
            "SELECT MAX(effectiveFromWeekEpoch) FROM workout_plan_items wpi2 " +
            "WHERE wpi2.exercise = wpi.exercise AND wpi2.effectiveFromWeekEpoch <= :weekStartMs" +
            ") AND effectiveFromWeekEpoch <= :weekStartMs"
    )
    suspend fun currentItems(weekStartMs: Long): List<WorkoutPlanItem>
}
