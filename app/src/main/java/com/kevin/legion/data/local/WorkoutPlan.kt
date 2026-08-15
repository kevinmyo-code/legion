package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The weekly-level half of a workout plan (`.scratch/legion-shape/issues/08-workouts-domain.md`,
 * D20/D21/D24). Same "copy forward, nothing deleted" shape [BudgetTarget] already established for
 * a periodic target (`.scratch/legion-shape/issues/05-target-log-gap-vocabulary.md` D1/D2): a row
 * is written only when the plan CHANGES, dated [effectiveFromWeekEpoch], and
 * [WorkoutPlanDao.currentPlan] reads "the most recent row on or before this week".
 *
 * [sessionsPerWeek]: a REASONED ADDITION beyond D20's literal text, flagged here the same way
 * [BudgetTarget.currency] flags its own reasoned addition. D20 defines a plan purely as
 * "exercises per week, with target sets" (see [WorkoutPlanItem]) - it names no session count at
 * all. D24 then defines the domain's one required gap as "sessions done versus sessions planned,
 * this week" and explicitly rules out substituting per-exercise adherence for it. Those two
 * decisions do not compose without a number CALLED "sessions planned" living somewhere, and
 * [WorkoutPlanItem]'s per-exercise set target cannot supply it (a set count is not a day count).
 * This field is that number - a single whole-plan target for how many distinct days a week the
 * driver intends to train, independent of which exercises happen on which day. **Flagged in the
 * build report as a decision needing Kevin's confirmation, not a fact read out of the ticket.**
 */
@Entity(
    tableName = "workout_plans",
    indices = [Index(value = ["effectiveFromWeekEpoch"], unique = true)],
)
data class WorkoutPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionsPerWeek: Int,
    /** UTC week-start (Monday) epoch millis - the first week this plan applies to. */
    val effectiveFromWeekEpoch: Long,
    val updatedAt: Long,
)
