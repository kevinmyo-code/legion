package com.kevin.legion.workouts

import android.util.Log
import com.kevin.legion.ai.SubAgent
import org.json.JSONObject

/** [WorkoutPlanAgent.write]'s result. */
data class WorkoutPlanDraft(val sessionsPerWeek: Int, val exercises: Map<String, Int>)

/**
 * D21: "The AI writes the plan, and the plan is a REPORTED fact - it is the model saying so."
 * Same one-shot [SubAgent] shape [com.kevin.legion.ledger.CategoryAgent] already uses, per this
 * codebase's convention of reusing the cheap-worker pattern rather than inventing a second one.
 *
 * D20 keeps a plan deliberately loose: "exercises per week, with target sets. No periodisation,
 * no progression model, no 1RM percentages." The prompt below asks for exactly that shape and
 * nothing more.
 */
object WorkoutPlanAgent {
    private const val TAG = "WorkoutPlanAgent"

    private val SYSTEM_INSTRUCTION = "You write loose weekly workout plans: which exercises, and " +
        "how many total sets of each per week. No periodisation, no progression scheme, no percent-" +
        "of-max numbers - just exercises and a weekly set target for each, plus how many separate " +
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
            "implies training), \"exercises\": [{\"name\": string, \"targetSetsPerWeek\": number}]}"

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
            for (i in 0 until exercisesArray.length()) {
                val o = exercisesArray.optJSONObject(i) ?: continue
                val name = o.optString("name").trim()
                val sets = o.optInt("targetSetsPerWeek", -1)
                if (name.isNotBlank() && sets > 0) exercises[name] = sets
            }
            if (sessionsPerWeek <= 0 || exercises.isEmpty()) null
            else WorkoutPlanDraft(sessionsPerWeek, exercises)
        } catch (e: Exception) {
            Log.w(TAG, "plan response malformed: ${e.message}")
            null
        }
    }
}
