package com.kevin.legion.meals

import android.util.Log
import com.kevin.legion.ai.SubAgent
import org.json.JSONObject

/** [MealAgent]'s result - every field is an ESTIMATE, never fact, see [MealAgent]'s doc comment. */
data class MealEstimate(
    val description: String,
    val caloriesKcal: Int?,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
)

/**
 * D25/D28: "A meal is logged by voice OR photo... Macros come from the LLM, labelled as
 * estimates." Same [SubAgent] one-shot vision shape
 * [com.kevin.legion.pantry.PantryReceiptAgent] already established for a photographed grocery
 * receipt - followed here per the build brief's explicit instruction to use it as the working
 * precedent - but WITHOUT that agent's reconciliation gate: a plate of food never prints a total
 * to check line items against, so there is nothing to reconcile, ever, not even provisionally
 * (see [com.kevin.legion.data.local.MealLog]'s doc comment). Every number this object returns is
 * an estimate and must be phrased as one everywhere it surfaces (CLAUDE.md §4 rule 5), never
 * accepted or rejected by a gate the way [PantryReceiptAgent] would.
 *
 * [estimateFromDescription] is what the voice tool (`log_meal` in
 * [com.kevin.legion.service.LiveToolbox]) calls. [estimateFromPhoto] exists for a future
 * photo-capture screen this pass does not build (UI is out of scope per the build brief except
 * where a domain is unreachable without it, and voice already makes meals reachable) - it is
 * wired and ready, just has no caller yet.
 */
object MealAgent {
    private const val TAG = "MealAgent"

    private val SYSTEM_INSTRUCTION = "You estimate calories and macros (protein, carbs, fat) for " +
        "a described or photographed meal, from your general nutrition knowledge. You are NEVER " +
        "given a printed total to match - there is nothing to reconcile against, so give your best " +
        "honest estimate rather than a suspiciously round number. If you truly cannot estimate an " +
        "axis, use null for it rather than guessing wildly."

    private const val PROMPT_SHAPE = "Respond with ONLY a raw JSON object (no markdown, no " +
        "commentary, no code fences) with this exact shape:\n" +
        "{\"description\": string (a short clean description of the meal), " +
        "\"caloriesKcal\": number or null (estimate), \"proteinG\": number or null (estimate), " +
        "\"carbsG\": number or null (estimate), \"fatG\": number or null (estimate)}"

    suspend fun estimateFromDescription(spokenDescription: String): MealEstimate? {
        val prompt = "Estimate calories and macros for this meal, described by the user: " +
            "\"$spokenDescription\". $PROMPT_SHAPE"
        val raw = try {
            SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false).ask(context = "", question = prompt)
        } catch (e: Exception) {
            Log.w(TAG, "meal estimate request failed: ${e.message}")
            null
        } ?: return null
        return parse(raw, fallbackDescription = spokenDescription)
    }

    /** See this object's doc comment - not called from anywhere yet (no photo-capture UI this pass). */
    suspend fun estimateFromPhoto(imageBytes: ByteArray): MealEstimate? {
        val prompt = "Estimate calories and macros for the meal in this photo. $PROMPT_SHAPE"
        val raw = try {
            SubAgent(systemInstruction = SYSTEM_INSTRUCTION, useSearch = false)
                .ask(context = "", question = prompt, imageBytes = imageBytes)
        } catch (e: Exception) {
            Log.w(TAG, "meal photo estimate request failed: ${e.message}")
            null
        } ?: return null
        return parse(raw, fallbackDescription = "photographed meal")
    }

    /** Network-free: the raw model text in, a typed estimate out. Unit-tested directly. */
    fun parse(raw: String, fallbackDescription: String): MealEstimate? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start == -1 || end == -1 || end < start) return null

        return try {
            val root = JSONObject(raw.substring(start, end + 1))
            val description = root.optString("description").trim().ifBlank { fallbackDescription }
            MealEstimate(
                description = description,
                caloriesKcal = root.optInt("caloriesKcal", -1).takeIf { it >= 0 && root.has("caloriesKcal") && !root.isNull("caloriesKcal") },
                proteinG = root.optDouble("proteinG").takeIf { !it.isNaN() },
                carbsG = root.optDouble("carbsG").takeIf { !it.isNaN() },
                fatG = root.optDouble("fatG").takeIf { !it.isNaN() },
            )
        } catch (e: Exception) {
            Log.w(TAG, "meal estimate response malformed: ${e.message}")
            null
        }
    }
}
