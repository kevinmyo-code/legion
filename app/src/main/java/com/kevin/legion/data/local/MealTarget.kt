package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The driver's daily calorie/macro target (`.scratch/legion-shape/issues/09-meals-domain.md`
 * D26: "The target is daily calories and macros"). Same copy-forward shape as [BudgetTarget] and
 * [WorkoutPlan] (ticket 05 D1/D2): a row is written only when the target CHANGES, dated
 * [effectiveFromDateEpoch], and [MealTargetDao.currentTarget] reads the latest row on or before
 * the day in question - "copy forward" computed at read time, nothing ever deleted.
 *
 * Unlike [BudgetTarget] there is no per-category dimension here - D26 names ONE daily target
 * (calories + three macros), not a target per food category, so this table holds at most one
 * effective row at a time rather than one per key.
 *
 * No [com.kevin.legion.plan.TrustTier] column, matching [BudgetTarget]/[WorkoutPlanItem]'s
 * precedent: ticket 05 D3 places a target OUTSIDE both trust tiers entirely - it is an intention,
 * not a claim about the world.
 */
@Entity(
    tableName = "meal_targets",
    indices = [Index(value = ["effectiveFromDateEpoch"], unique = true)],
)
data class MealTarget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val caloriesKcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** UTC day-start epoch millis - the first day this target applies to. */
    val effectiveFromDateEpoch: Long,
    val updatedAt: Long,
)
