package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import com.kevin.legion.finance.noteOrNull
import com.kevin.legion.finance.valueOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure shaping of the engine's body-coach bodies for the voice tools, the BIO digest and the plan
 * generator (`.scratch/fat-loss/` tickets 10 to 12). No Context, no network, so a plain JUnit test
 * reaches every branch.
 *
 * **Every calorie and protein figure is an estimate** (CLAUDE.md section 4 rule 5): the JSON says
 * `"estimate": true` and the engine's own `words` travel with it. A saved copy carries its age in
 * words; an unreadable engine says so and offers no figure.
 */
object BodyCoachText {

    /** How a pending proposal is accepted by voice: an engine tool, through `ask_engine`. */
    fun acceptHint(proposalId: String): String =
        "To accept it after the user's plain yes, call ask_engine with intent \"record\" and ask it " +
            "to accept energy proposal $proposalId; to decline, dismiss energy proposal $proposalId. " +
            "Never accept without that yes."

    /** `get_energy_estimate`'s result. */
    fun energyResult(read: FinanceRead<EnergyWire>): JSONObject {
        val e = read.valueOrNull ?: return unavailable(read)
        val out = JSONObject()
            .put("success", true)
            .put("estimate", true)
            .put("words", e.words)
            .put("basis", e.basis ?: JSONObject.NULL)
            .put("basis_words", e.basisWords)
        e.basisDays?.let { out.put("basis_days", it) }
        if (e.missing.isNotEmpty()) out.put("missing", JSONArray(e.missing))
        e.weightLbs?.let { out.put("weight_lbs", it).put("weight_source", e.weightSource ?: JSONObject.NULL) }
        e.bmrKcal?.let { out.put("bmr_kcal", it) }
        e.maintenanceKcal?.let { out.put("maintenance_kcal", it) }
        e.formulaMaintenanceKcal?.let { out.put("formula_maintenance_kcal", it) }
        e.observedMaintenanceKcal?.let { out.put("observed_maintenance_kcal", it) }
        e.targetRatePct?.let { out.put("target_rate_pct_bodyweight_per_week", it) }
        e.suggestedTargetKcal?.let { out.put("suggested_target_kcal", it) }
        e.proteinG?.let { out.put("protein_g", it).put("protein_basis", e.proteinBasis ?: JSONObject.NULL) }
        e.currentTarget?.let { t ->
            out.put(
                "current_target",
                JSONObject().put("calories_kcal", t.caloriesKcal).put("protein_g", t.proteinG ?: JSONObject.NULL),
            )
        }
        e.proposal?.let { p ->
            out.put(
                "pending_proposal",
                JSONObject()
                    .put("id", p.id)
                    .put("current_target_kcal", p.currentTargetKcal)
                    .put("proposed_target_kcal", p.proposedTargetKcal)
                    .put("words", p.words)
                    .put("how_to_accept", acceptHint(p.id)),
            )
        }
        read.noteOrNull?.let { out.put("note", it) }
        return out
    }

    /** `get_weight_trend`'s result: the summary and the last [recentPoints] days, never 90 rows. */
    fun trendResult(read: FinanceRead<TrendWire>, recentPoints: Int = 14): JSONObject {
        val t = read.valueOrNull ?: return unavailable(read)
        val out = JSONObject()
            .put("success", true)
            .put("unit", t.unit)
            .put("words", t.words)
            .put("early_water", t.earlyWater)
        t.latestTrendLbs?.let { out.put("trend_lbs", it) }
        t.rateLbsPerWeek?.let { out.put("rate_lbs_per_week", it) }
        t.ratePctPerWeek?.let { out.put("rate_pct_bodyweight_per_week", it) }
        t.inBand?.let { out.put("in_band_0_5_to_1_0_pct", it) }
        t.milestone?.let { m ->
            out.put(
                "milestone",
                JSONObject()
                    .put("statement", m.statement)
                    .put("target_lbs", m.targetLbs)
                    .put("remaining_lbs", m.remainingLbs ?: JSONObject.NULL)
                    .put("progress_pct", m.progressPct ?: JSONObject.NULL)
                    .put("weeks_to_go", m.weeksToGo ?: JSONObject.NULL),
            )
        }
        t.waist.firstOrNull()?.let { w ->
            out.put("latest_waist", JSONObject().put("measured_on", w.measuredOn).put("waist_in", w.waistIn ?: JSONObject.NULL))
        }
        t.waistChangeIn28d?.let { out.put("waist_change_in_28_days", it) }
        val recent = JSONArray()
        t.points.takeLast(recentPoints).forEach { p ->
            recent.put(
                JSONObject().put("day", p.day).put("weight_lbs", p.weightLbs ?: JSONObject.NULL).put("trend_lbs", p.trendLbs),
            )
        }
        out.put("recent_days", recent)
        read.noteOrNull?.let { out.put("note", it) }
        return out
    }

    /** `list_saved_meals`'s result. */
    fun savedMealsResult(read: FinanceRead<SavedMealsWire>): JSONObject {
        val list = read.valueOrNull ?: return unavailable(read)
        val meals = JSONArray()
        list.results.forEach { m ->
            meals.put(
                JSONObject()
                    .put("name", m.name)
                    .put("calories_kcal", m.caloriesKcal ?: JSONObject.NULL)
                    .put("protein_g", m.proteinG ?: JSONObject.NULL)
                    .apply { m.description?.let { put("description", it) } },
            )
        }
        val out = JSONObject()
            .put("success", true)
            .put("estimate", true)
            .put("saved_meals", meals)
            .put(
                "words",
                if (list.results.isEmpty()) {
                    "No saved meals yet."
                } else {
                    "${list.results.size} saved meals; their calories and protein are estimates."
                },
            )
        read.noteOrNull?.let { out.put("note", it) }
        return out
    }

    /**
     * The BIO digest's ENERGY line value (ticket 12), from a saved copy: maintenance and its basis,
     * the suggested target, protein, a pending proposal. Null when there is no copy at all, so the
     * caller writes "not available" with the reason.
     */
    fun digestEnergy(e: EnergyWire): String {
        val parts = mutableListOf<String>()
        e.maintenanceKcal?.let { k ->
            val basis = when (e.basis) {
                "trend" -> "from trend ${e.basisDays ?: "?"}d"
                "formula" -> "from formula"
                else -> "basis unknown"
            }
            parts += "maintenance ${k}kcal ($basis)"
        }
        e.suggestedTargetKcal?.let { parts += "suggested ${it}kcal at ${e.targetRatePct ?: "?"}%BW/wk" }
        e.proteinG?.let { parts += "protein ${it}g (${e.proteinBasis ?: "bodyweight"})" }
        e.currentTarget?.let { parts += "current target ${it.caloriesKcal}kcal" }
        e.proposal?.let { parts += "pending proposal ${it.currentTargetKcal}->${it.proposedTargetKcal}kcal" }
        if (e.missing.isNotEmpty()) parts += "missing: ${e.missing.joinToString(", ")}"
        return parts.joinToString(" ").ifBlank { "no estimate" }
    }

    private fun unavailable(read: FinanceRead<*>): JSONObject =
        JSONObject()
            .put("success", false)
            .put("message", (read.noteOrNull ?: "The engine's body figures could not be read.") + " Nothing was estimated.")

    // --- Saved meals in log_meal (ticket 10, map decision 4) -----------------------------------

    /** Words a person wraps a saved meal's name in ("log my usual breakfast"), never the name. */
    private val FILLER = setOf(
        "log", "logged", "add", "i", "had", "ate", "have", "just", "my", "the", "a", "an", "our",
        "usual", "regular", "normal", "standard", "typical", "same", "saved", "again", "as",
        "always", "today", "for", "please", "meal", "one",
    )

    /** Words that say he means a saved meal even when the rest only overlaps its name. */
    private val MARKERS = setOf("usual", "regular", "normal", "standard", "typical", "same", "saved")

    private fun words(text: String): List<String> =
        text.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() }

    private fun core(text: String): String = words(text).filterNot { it in FILLER }.joinToString(" ")

    /** Whether [description] carries a word that says a saved meal is meant ("my usual ..."). */
    fun namesASavedMeal(description: String): Boolean = words(description).any { it in MARKERS }

    /**
     * The saved meal [description] names, or null. **Exact after filler words are dropped** ("my
     * usual breakfast" is "breakfast"; a saved "Usual breakfast" is "breakfast"), so "chicken
     * burrito bowl" never silently becomes a saved "burrito bowl". With a marker word ("usual",
     * "regular", "saved"...) one saved meal whose name overlaps is enough; two such candidates is no
     * match, never a guess.
     */
    @Suppress("ReturnCount")
    fun matchSavedMeal(description: String, meals: List<SavedMealWire>): SavedMealWire? {
        val said = core(description)
        if (said.isBlank()) return null
        meals.firstOrNull { core(it.name) == said }?.let { return it }
        if (words(description).none { it in MARKERS }) return null
        val loose = meals.filter { m ->
            val name = core(m.name)
            name.isNotBlank() && (name.contains(said) || said.contains(name))
        }
        return loose.singleOrNull()
    }
}
