package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

/** Data Access Object for [WorkoutPlan] - the weekly session-count half of the plan. */
@Dao
interface WorkoutPlanDao {
    /** REPLACE on the unique `effectiveFromWeekEpoch` index, same reasoning as [BudgetTargetDao.upsert]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(plan: WorkoutPlan)

    /** Whole-row update - [com.kevin.legion.backend.BodySync.pull]'s merge write. Body-supabase
     * ticket; see [MealTargetDao.update]'s own doc comment for why nothing else calls this. */
    @Update
    suspend fun update(plan: WorkoutPlan)

    /** Every row, active AND soft-deleted (body-supabase ticket) - see
     * [BodyweightLogDao.getAll]'s own doc comment for why. */
    @Query("SELECT * FROM workout_plans")
    suspend fun getAll(): List<WorkoutPlan>

    /** The EXACT row already effective from [weekStartMs] - see [MealTargetDao.getByEffectiveDate]'s
     * own doc comment for why [com.kevin.legion.workouts.WorkoutController.generatePlan] reuses
     * this row's guid on a same-week re-plan rather than minting a fresh one. */
    @Query("SELECT * FROM workout_plans WHERE effectiveFromWeekEpoch = :weekStartMs LIMIT 1")
    suspend fun getByEffectiveWeek(weekStartMs: Long): WorkoutPlan?

    /** The plan effective as of [weekStartMs] - the latest row on or before it, i.e. "copy forward".
     * `deleted = 0` added body-supabase ticket, same reasoning as [MealTargetDao.currentTarget]. */
    @Query(
        "SELECT * FROM workout_plans WHERE deleted = 0 AND effectiveFromWeekEpoch = (" +
            "SELECT MAX(effectiveFromWeekEpoch) FROM workout_plans WHERE deleted = 0 AND effectiveFromWeekEpoch <= :weekStartMs" +
            ")"
    )
    suspend fun currentPlan(weekStartMs: Long): WorkoutPlan?
}
