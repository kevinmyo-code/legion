package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [WorkoutPlanItem] - the per-exercise target-sets half of the plan. */
@Dao
interface WorkoutPlanItemDao {
    /** REPLACE on the unique `(exercise, effectiveFromWeekEpoch)` index, same shape as [BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: WorkoutPlanItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<WorkoutPlanItem>)

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write. Body-supabase
     * ticket; see [MealTargetDao.update]'s own doc comment for why nothing else calls this. */
    @Update
    suspend fun update(item: WorkoutPlanItem)

    /** Every row, active AND soft-deleted (body-supabase ticket) - see
     * [BodyweightLogDao.getAll]'s own doc comment for why. */
    @Query("SELECT * FROM workout_plan_items")
    suspend fun getAll(): List<WorkoutPlanItem>

    /** The EXACT row for [exercise] already effective from [weekStartMs] - see
     * [MealTargetDao.getByEffectiveDate]'s own doc comment for why
     * [com.kevin.legion.workouts.WorkoutController.generatePlan] reuses this row's guid on a
     * same-week re-plan rather than minting a fresh one. */
    @Query("SELECT * FROM workout_plan_items WHERE exercise = :exercise AND effectiveFromWeekEpoch = :weekStartMs LIMIT 1")
    suspend fun getByExerciseAndWeek(exercise: String, weekStartMs: Long): WorkoutPlanItem?

    /**
     * Every exercise's currently-effective target as of [weekStartMs] - one row per exercise, the
     * latest whose [WorkoutPlanItem.effectiveFromWeekEpoch] is on or before the week being asked
     * about. Same "copy forward" read [BudgetTargetDao.currentTargets] performs. `deleted = 0`
     * added body-supabase ticket, same reasoning as [MealTargetDao.currentTarget].
     */
    @Query(
        "SELECT * FROM workout_plan_items wpi WHERE deleted = 0 AND effectiveFromWeekEpoch = (" +
            "SELECT MAX(effectiveFromWeekEpoch) FROM workout_plan_items wpi2 " +
            "WHERE wpi2.exercise = wpi.exercise AND wpi2.deleted = 0 AND wpi2.effectiveFromWeekEpoch <= :weekStartMs" +
            ") AND effectiveFromWeekEpoch <= :weekStartMs"
    )
    suspend fun currentItems(weekStartMs: Long): List<WorkoutPlanItem>
}
