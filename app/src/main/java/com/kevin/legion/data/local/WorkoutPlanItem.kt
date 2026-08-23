package com.kevin.legion.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One exercise's slice of a workout plan (`.scratch/legion-shape/issues/08-workouts-domain.md`
 * D20): "A plan is loose: exercises per week, with target sets. No periodisation, no progression
 * model, no 1RM percentages." Deliberately just [exercise] + [targetSetsPerWeek] - no weight, no
 * day-of-week slotting; those live on the LOG side ([WorkoutSetLog]), not the plan. [repsPerSet]
 * (v32, `goal-plans` ticket 08) is the one narrow exception: Kevin's own ruling ("3 sets x 10 rep
 * kettlebell swing") asked the daily checklist line to read like a real prescription, and a set
 * count with no rep count reads as half of one. **Nullable, and NEVER backfilled for an old row** -
 * a plan written before this column existed said nothing about reps, and inventing a number for it
 * would be exactly the fabricated-prescription CLAUDE.md §4 rule 5 forbids for a receipt; a null
 * here means the checklist renders sets-only for that line, same as it always did.
 *
 * Same copy-forward shape as [BudgetTarget]/[WorkoutPlan]: a row is written only when an
 * exercise's target changes, and [WorkoutPlanItemDao.currentItems] reads the latest row per
 * exercise on or before the week in question.
 *
 * D21: "The AI writes the plan, and the plan is a REPORTED fact... the resolution is that the
 * plan's EXISTENCE is a target." Per ticket 05 D3 a target sits outside both trust tiers
 * entirely - it is an intention, not a claim about the world - so this row carries no
 * [com.kevin.legion.plan.TrustTier] column at all, matching [BudgetTarget]'s and
 * [MealTarget]'s precedent of leaving targets untagged.
 */
@Entity(
    tableName = "workout_plan_items",
    indices = [Index(value = ["exercise", "effectiveFromWeekEpoch"], unique = true)],
)
data class WorkoutPlanItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exercise: String,
    val targetSetsPerWeek: Int,
    /** UTC week-start (Monday) epoch millis, same convention as [WorkoutPlan.effectiveFromWeekEpoch]. */
    val effectiveFromWeekEpoch: Long,
    val updatedAt: Long,
    /** Reps per set, when the plan states one - see the class doc. Null for every row written
     * before v32 and for any future plan whose source (model or hand-entry) simply didn't say. */
    @ColumnInfo(defaultValue = "NULL") val repsPerSet: Int? = null,
)
