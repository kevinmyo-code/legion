package com.kevin.legion.workouts

import android.util.Log
import com.kevin.legion.ai.SubAgent
import org.json.JSONObject

/**
 * [WorkoutPlanAgent.write]'s result. [repsPerSet] (goal-plans ticket 08) is a SEPARATE map, not a
 * widened value type on [exercises], so an exercise the model gave no rep count for simply has no
 * entry here rather than a fabricated one - [exercises]`[name]` stays the pre-existing "sets" fact,
 * `repsPerSet[name]` is the newer, optional "reps" fact, and the two can disagree on which
 * exercises they cover without either one lying about it.
 */
data class WorkoutPlanDraft(
    val sessionsPerWeek: Int,
    val exercises: Map<String, Int>,
    val repsPerSet: Map<String, Int> = emptyMap(),
)

/**
 * D21: "The AI writes the plan, and the plan is a REPORTED fact - it is the model saying so."
 * Same one-shot [SubAgent] shape [com.kevin.legion.ledger.CategoryAgent] already uses, per this
 * codebase's convention of reusing the cheap-worker pattern rather than inventing a second one.
 *
 * D20 keeps a plan deliberately loose: "exercises per week, with target sets. No periodisation,
 * no progression model, no 1RM percentages." The prompt below asks for exactly that shape, plus
 * (goal-plans ticket 08) an OPTIONAL reps-per-set the model may state alongside the set count -
 * "3 sets x 10 rep kettlebell swing" is Kevin's own example of what a daily line should read like,
 * and a set count with no rep figure is only half of that. Optional and unenforced: a model that
 * omits it for an exercise leaves that exercise's reps null all the way through to the checklist,
 * never guessed at here or anywhere downstream (CLAUDE.md §4 rule 5 - not stated is not gated).
 */
object WorkoutPlanAgent {
    private const val TAG = "WorkoutPlanAgent"

    private val SYSTEM_INSTRUCTION = "You write loose weekly workout plans: which exercises, and " +
        "how many total sets of each per week, plus how many reps per set when that's a sensible " +
        "thing to state for the exercise (omit reps for something like a timed plank or a walk). " +
        "No periodisation, no progression scheme, no percent-of-max numbers - just exercises, a " +
        "weekly set target for each, an optional reps-per-set for each, plus how many separate " +
        "days a week the plan implies training. Keep it to 3-8 exercises unless the user's goal " +
        "clearly calls for more."

    /**
     * [goal] is the driver's own words (e.g. "I want to build a basic push/pull/legs routine,
     * three days a week"). Returns null on any failure so the caller can speak a fallback instead
     * of a half-formed plan.
     */
    suspend fun write(goal: String): WorkoutPlanDraft? {
        val prompt = "Write a loose weekly workout plan for this goal: \"$goal\". Respond with " +
            "ONLY a raw JSON object (no markdown, no commentary, no code fences) with this exact " +
            "shape:\n{\"sessionsPerWeek\": number (how many separate days a week this plan " +
            "implies training), \"exercises\": [{\"name\": string, \"targetSetsPerWeek\": number, " +
            "\"repsPerSet\": number (optional - omit entirely when a rep count doesn't make sense " +
            "for this exercise)}]}"

        val raw = try {
            SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false).ask(context = "", question = prompt)
        } catch (e: Exception) {
            Log.w(TAG, "plan request failed: ${e.message}")
            null
        } ?: return null

        return parse(raw)
    }

    /** Network-free: the raw model text in, a typed draft out. Unit-tested directly. */
    fun parse(raw: String): WorkoutPlanDraft? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null

        return try {
            val root = JSONObject(raw.substring(start, end + 1))
            val sessionsPerWeek = root.optInt("sessionsPerWeek", -1)
            val exercisesArray = root.optJSONArray("exercises") ?: return null
            val exercises = mutableMapOf<String, Int>()
            val repsPerSet = mutableMapOf<String, Int>()
            for (i in 0 until exercisesArray.length()) {
                val o = exercisesArray.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val sets = o.optInt("targetSetsPerWeek", -1)
                if (name.isNotBlank() && sets > 0) exercises[name] = sets
                // optInt's own -1 default distinguishes "the field was absent/zero" from a real
                // rep count - never store a 0 or a missing field as a fabricated rep prescription.
                val reps = o.optInt("repsPerSet", -1)
                if (name.isNotBlank() && sets > 0 && reps > 0) repsPerSet[name] = reps
            }
            if (sessionsPerWeek <= 0 || exercises.isEmpty()) null
            else WorkoutPlanDraft(sessionsPerWeek, exercises, repsPerSet)
        } catch (e: Exception) {
            Log.w(TAG, "plan response malformed: ${e.message}")
            null
        }
    }
}
