package com.kevin.legion.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kevin.legion.plan.TrustTier

/**
 * One logged meal (`.scratch/legion-shape/issues/09-meals-domain.md` D25/D28): "A meal is logged
 * by voice OR photo... Macros come from the LLM, labelled as estimates." Unlike
 * [com.kevin.legion.data.local.PantryReceipt] there is no printed total anywhere to reconcile
 * against - a plate of food, unlike a grocery receipt, never states its own calorie count - so
 * CLAUDE.md §4's reconciliation gate does not apply here AT ALL, not even in the "provisional, no
 * anchor" shape §4 rule 7 describes for a document that states no anchor. This is a domain that
 * NEVER has an anchor, by the nature of the thing being logged; every row here is
 * [TrustTier.REPORTED] unconditionally, same as [WorkoutSetLog]/[BodyweightLog].
 *
 * [caloriesKcal]/[proteinG]/[carbsG]/[fatG] are all nullable AND all estimates - the LLM's guess
 * from [description] (or from a photo, for the future photo-logging path -
 * [com.kevin.legion.meals.MealAgent.estimateFromPhoto] already exists for it; only the voice tool
 * is wired in this pass, see the build report). Nullable because an extraction can legitimately
 * fail to produce a usable number for one axis without the whole log being worthless the way a
 * ledger row would be on a reconciliation miss - there is no gate here to fail.
 *
 * [sourceImagePath] is null for a voice-only log; set when a photo produced this row (once the
 * photo-capture UI exists to call [com.kevin.legion.meals.MealAgent.estimateFromPhoto]).
 */
@Entity(tableName = "meal_logs")
data class MealLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val caloriesKcal: Int? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val loggedAt: Long,
    val sourceImagePath: String? = null,
    val trustTier: TrustTier,
)
