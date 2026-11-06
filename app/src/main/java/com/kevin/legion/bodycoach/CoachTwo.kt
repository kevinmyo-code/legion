package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/*
 * Body coach 2 (`.scratch/body-coach-2/`, Kevin 2026-11-06): "what should I eat" from what is left,
 * the alternatives to an exercise, and a pending programme change. **The engine computes all of it**
 * (`server/body_coach/suggest.py`, `programme_change.py`); the phone says the engine's words and
 * hands the model the engine's options, so "state only what is here" still lets it suggest one.
 */

/** `GET /api/body/coach/meal-options/`. Every figure is an ESTIMATE. */
@Serializable
data class MealOptionsWire(
    val clock: String = "",
    @SerialName("part_of_day") val partOfDay: String = "",
    /** ok, no_target, over, late_little_left, under_floor. */
    val state: String = "",
    val target: MealTargetWire = MealTargetWire(),
    val eaten: MealEatenWire = MealEatenWire(),
    @SerialName("kcal_left") val kcalLeft: Int? = null,
    @SerialName("protein_left_g") val proteinLeftG: Double? = null,
    @SerialName("this_meal_kcal") val thisMealKcal: Int? = null,
    val options: List<MealOptionWire> = emptyList(),
    @SerialName("too_big_as_bought") val tooBigAsBought: List<String> = emptyList(),
    val words: String = "",
)

@Serializable
data class MealTargetWire(
    val kcal: Int? = null,
    @SerialName("kcal_basis") val kcalBasis: String? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
)

/** Carbs and fat are null when no meal today states them (kcal and protein are the targets). */
@Serializable
data class MealEatenWire(
    val kcal: Int = 0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    val meals: Int = 0,
)

@Serializable
data class MealOptionWire(
    val rank: Int = 0,
    /** `saved_meal:<uuid>`, `grocery:<uuid>` or `combo:<uuid>+<uuid>`; logged by this id only. */
    val id: String,
    val source: String = "",
    val name: String = "",
    val kcal: Int = 0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    val basis: String = "",
    val why: String = "",
)

/** `GET /api/body/coach/workout/alternatives/<exercise>/`. */
@Serializable
data class AlternativesWire(
    val exercise: String = "",
    val options: List<AlternativeWire> = emptyList(),
    val words: String = "",
)

@Serializable
data class AlternativeWire(
    val name: String = "",
    val equipment: List<String> = emptyList(),
    val joints: List<String> = emptyList(),
    val allowed: Boolean = true,
    @SerialName("why_not") val whyNot: String? = null,
    @SerialName("progress_carries") val progressCarries: Boolean = false,
)

/** A pending programme change on `GET workout/` (`proposal`), shown as one line with Accept. */
@Serializable
data class ProgrammeProposalWire(
    val id: String,
    val words: String = "",
    @SerialName("after_summary") val afterSummary: String = "",
)

/** The plan draft on `GET workout/` (`plan_draft`): one line on the checklist, Accept locks it in. */
@Serializable
data class PlanDraftLineWire(
    val id: String,
    val words: String = "",
    val summary: String = "",
)

object CoachTwoText {

    /** How a plan change or a suggested meal is written by voice: an engine tool, via `ask_engine`. */
    const val WRITE_HINT =
        "To log one after the user picks it, call ask_engine with intent \"record\" and ask it to log " +
            "meal option <its id> (log_meal_option). Never pass a figure; the engine recomputes it."

    /** `suggest_meal`'s result: the engine's words and its ranked options, each with an id. */
    fun mealOptionsResult(read: FinanceRead<MealOptionsWire>): JSONObject {
        val m = read.valueOrNull ?: return unavailable(read, "Nothing is known about what is left today.")
        val options = JSONArray()
        for (o in m.options) {
            options.put(
                JSONObject()
                    .put("rank", o.rank)
                    .put("option_id", o.id)
                    .put("name", o.name)
                    .put("source", o.source)
                    .put("kcal", o.kcal)
                    .put("protein_g", o.proteinG)
                    .put("basis", o.basis)
                    .put("why", o.why),
            )
        }
        val out = JSONObject()
            .put("success", true)
            .put("estimate", true)
            .put("words", m.words)
            .put("state", m.state)
            .put("clock", m.clock)
            .put("kcal_left", m.kcalLeft ?: JSONObject.NULL)
            .put("protein_left_g", m.proteinLeftG ?: JSONObject.NULL)
            .put("options", options)
            .put(
                "rule",
                "These options are the engine's, figures are estimates. Suggest only from them, in " +
                    "this order unless the user prefers another; never invent a meal or a figure, never " +
                    "push the user under 800 kcal or toward skipping food. $WRITE_HINT",
            )
        read.noteOrNull?.let { out.put("saved_copy", it) }
        return out
    }

    /** `get_exercise_alternatives`'s result. */
    fun alternativesResult(read: FinanceRead<AlternativesWire>): JSONObject {
        val a = read.valueOrNull ?: return unavailable(read, "The alternatives are unknown here.")
        val options = JSONArray()
        for (o in a.options) {
            options.put(
                JSONObject()
                    .put("name", o.name)
                    .put("allowed", o.allowed)
                    .put("why_not", o.whyNot ?: JSONObject.NULL)
                    .put("equipment", JSONArray(o.equipment))
                    .put("progress_carries", o.progressCarries),
            )
        }
        val out = JSONObject()
            .put("success", true)
            .put("exercise", a.exercise)
            .put("words", a.words)
            .put("options", options)
            .put(
                "rule",
                "Recommend only from these. To change the plan, call ask_engine with intent \"record\" " +
                    "(propose_programme_change with swap_exercise and swap_to), read its proposal back, " +
                    "and accept only on the user's yes. To swap in today's workout only, swap_exercise.",
            )
        read.noteOrNull?.let { out.put("saved_copy", it) }
        return out
    }

    /** The proposal line on the workout card: its first sentence, which names the new shape. */
    fun proposalLine(p: ProgrammeProposalWire): String =
        p.words.substringBefore(". ").removeSuffix(".").ifBlank { "Plan change: ${p.afterSummary}" } + "."

    /** The draft line: "Lock in your plan: about 2000 kcal ...". */
    fun draftLine(d: PlanDraftLineWire): String =
        if (d.summary.isNotBlank()) "Lock in your plan: ${d.summary}." else d.words.substringBefore(". ") + "."

    private fun unavailable(read: FinanceRead<*>, tail: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("message", (read.noteOrNull ?: "The engine could not be read.") + " " + tail)
}
