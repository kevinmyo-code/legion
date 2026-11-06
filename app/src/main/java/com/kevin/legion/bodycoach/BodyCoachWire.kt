package com.kevin.legion.bodycoach

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The engine's body-coach bodies (`server/body_coach/`, `.scratch/fat-loss/` tickets 03 to 07),
 * field for field. Every calorie and protein figure here is an ESTIMATE (CLAUDE.md section 4 rule
 * 5) and the engine says so in [EnergyWire.words]; the phone repeats the engine's words rather than
 * composing its own sentence about a figure.
 */
internal val bodyCoachJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** `GET /api/body/coach/energy/`. A null figure is one its inputs could not support ([missing]). */
@Serializable
data class EnergyWire(
    val estimate: Boolean = true,
    /** `formula` or `trend`; null when nothing could be estimated. */
    val basis: String? = null,
    @SerialName("basis_days") val basisDays: Int? = null,
    @SerialName("basis_words") val basisWords: String = "",
    /** What the estimate still needs, in words ("height", "a weigh-in"). Empty when complete. */
    val missing: List<String> = emptyList(),
    @SerialName("weight_lbs") val weightLbs: Double? = null,
    @SerialName("weight_source") val weightSource: String? = null,
    @SerialName("bmr_kcal") val bmrKcal: Int? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
    @SerialName("activity_factor") val activityFactor: Double? = null,
    @SerialName("formula_maintenance_kcal") val formulaMaintenanceKcal: Int? = null,
    @SerialName("observed_maintenance_kcal") val observedMaintenanceKcal: Int? = null,
    @SerialName("maintenance_kcal") val maintenanceKcal: Int? = null,
    @SerialName("target_rate_pct") val targetRatePct: Double? = null,
    @SerialName("suggested_target_kcal") val suggestedTargetKcal: Int? = null,
    @SerialName("protein_g") val proteinG: Int? = null,
    @SerialName("protein_g_per_kg") val proteinGPerKg: Double? = null,
    @SerialName("protein_basis") val proteinBasis: String? = null,
    @SerialName("current_target") val currentTarget: CurrentTargetWire? = null,
    val proposal: ProposalWire? = null,
    val words: String = "",
    // Coach voice fixes (2026-11-06). Absent from an older engine: null means NOT SENT.
    /** The plan the estimate is said at: a waiting draft, else the locked plan. */
    val plan: EnergyPlanWire? = null,
    /** The profile the estimate stands on, so a stated fact that differs can be offered as an update. */
    val profile: EnergyProfileWire? = null,
)

@Serializable
data class EnergyPlanWire(
    val id: String = "",
    /** "draft" or "active". */
    val status: String = "",
    @SerialName("kcal_target") val kcalTarget: Int? = null,
    @SerialName("protein_g") val proteinG: Int? = null,
    @SerialName("rate_pct") val ratePct: Double? = null,
    @SerialName("target_set_by") val targetSetBy: String = "",
)

@Serializable
data class EnergyProfileWire(
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("height_ft_in") val heightFtIn: String? = null,
    val sex: String? = null,
    @SerialName("birth_year") val birthYear: Int? = null,
    @SerialName("age_about") val ageAbout: Int? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
)

@Serializable
data class CurrentTargetWire(
    @SerialName("calories_kcal") val caloriesKcal: Int,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("effective_from") val effectiveFrom: String? = null,
)

/** A pending two-weekly adjustment (ticket 06). Never applied until Kevin accepts it. */
@Serializable
data class ProposalWire(
    val id: String,
    @SerialName("current_target_kcal") val currentTargetKcal: Int,
    @SerialName("proposed_target_kcal") val proposedTargetKcal: Int,
    @SerialName("observed_maintenance_kcal") val observedMaintenanceKcal: Int? = null,
    @SerialName("observed_rate_pct") val observedRatePct: Double? = null,
    @SerialName("window_start") val windowStart: String? = null,
    @SerialName("window_end") val windowEnd: String? = null,
    @SerialName("intake_days") val intakeDays: Int? = null,
    @SerialName("weigh_in_days") val weighInDays: Int? = null,
    val words: String = "",
)

/** `GET /api/body/coach/trend/`. Weights in lbs; a negative rate is a loss. */
@Serializable
data class TrendWire(
    val unit: String = "lbs",
    val points: List<TrendPointWire> = emptyList(),
    @SerialName("latest_trend_lbs") val latestTrendLbs: Double? = null,
    @SerialName("rate_pct_per_week") val ratePctPerWeek: Double? = null,
    @SerialName("rate_lbs_per_week") val rateLbsPerWeek: Double? = null,
    @SerialName("rate_days") val rateDays: Int = 14,
    @SerialName("in_band") val inBand: Boolean? = null,
    @SerialName("early_water") val earlyWater: Boolean = false,
    val milestone: MilestoneWire? = null,
    val waist: List<WaistWire> = emptyList(),
    @SerialName("waist_change_in_28d") val waistChangeIn28d: Double? = null,
    val words: String = "",
)

@Serializable
data class TrendPointWire(
    val day: String,
    @SerialName("weight_lbs") val weightLbs: Double? = null,
    @SerialName("trend_lbs") val trendLbs: Double,
)

/** Progress toward the active `bodyweight_lbs` goal (G1), rate based. */
@Serializable
data class MilestoneWire(
    @SerialName("goal_id") val goalId: String? = null,
    val statement: String = "",
    @SerialName("target_lbs") val targetLbs: Double,
    @SerialName("start_lbs") val startLbs: Double? = null,
    @SerialName("remaining_lbs") val remainingLbs: Double? = null,
    @SerialName("progress_pct") val progressPct: Int? = null,
    @SerialName("weeks_to_go") val weeksToGo: Double? = null,
)

@Serializable
data class WaistWire(
    val id: String? = null,
    @SerialName("measured_on") val measuredOn: String,
    @SerialName("waist_in") val waistIn: Double? = null,
    @SerialName("waist_cm") val waistCm: Double? = null,
)

/** `GET /api/body/coach/saved-meals/`. Macros are estimates. */
@Serializable
data class SavedMealsWire(val results: List<SavedMealWire> = emptyList())

@Serializable
data class SavedMealWire(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("calories_kcal") val caloriesKcal: Int? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)

/** `GET /api/body/coach/weekly-line/`: whether the Monday line may speak, and its facts. */
@Serializable
data class WeeklyLineWire(
    val say: Boolean = false,
    val facts: String = "",
    val reason: String = "",
)

/** `GET /api/body/coach/profile/`: the caller's own profile, private to them (ADR 0052). */
@Serializable
data class ProfileWire(
    val exists: Boolean = false,
    @SerialName("height_cm") val heightCm: Double? = null,
    val sex: String? = null,
    @SerialName("birth_year") val birthYear: Int? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
    @SerialName("target_rate_pct") val targetRatePct: Double? = null,
    @SerialName("protein_g_per_kg") val proteinGPerKg: Double? = null,
    @SerialName("weekly_line_on") val weeklyLineOn: Boolean = true,
    /** The member's goal or milestone weight (G1): the engine's fallback when no goals row is there. */
    @SerialName("goal_weight_lbs") val goalWeightLbs: Double? = null,
    val missing: List<String> = emptyList(),
)
