package com.kevin.legion.data.local

import androidx.room.ColumnInfo
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
 *
 * **[guid]/[serverId]/[deleted] joined v59 -> v60 (body-supabase ticket)** - see [BodyweightLog]'s
 * own doc comment for the shared shape. **Deliberately NO separate `updatedAtMs`, unlike the four
 * body LOG tables.** [updatedAt] already exists, is stamped with `System.currentTimeMillis()` at
 * the exact moment every write happens ([com.kevin.legion.meals.MealController.setTarget]), and is
 * read by nothing else in this codebase - it already IS the mutation clock a sync merge needs, and
 * a second column carrying the identical value would be a fact with two owners that a future write
 * site could update one of and not the other (the same duplicate-source-of-truth shape
 * CLAUDE.md §4's `receipts.unaccounted_cents` note warns against for a different pair of columns).
 * [com.kevin.legion.backend.BodySync]'s merge logic reads [updatedAt] directly for this table
 * rather than a same-named [updatedAtMs] the other four tables carry.
 */
@Entity(
    tableName = "meal_targets",
    indices = [
        Index(value = ["effectiveFromDateEpoch"], unique = true),
        Index(value = ["guid"], unique = true),
    ],
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
    @ColumnInfo(defaultValue = "''") val guid: String = "",
    val serverId: String? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
)
