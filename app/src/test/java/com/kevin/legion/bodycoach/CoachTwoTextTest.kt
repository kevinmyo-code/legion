package com.kevin.legion.bodycoach

import com.kevin.legion.finance.FinanceRead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Body coach 2 (`.scratch/body-coach-2/` ticket 07): the meal options and the alternatives reach the
 * model as the engine's own options, each with its id, so it suggests from them and logs by id.
 * Under Robolectric only for `org.json`. Synthetic figures.
 */
@RunWith(RobolectricTestRunner::class)
class CoachTwoTextTest {

    private val body = """
        {"clock": "12:30", "part_of_day": "midday", "state": "ok",
         "target": {"kcal": 2000, "kcal_basis": "meal target", "protein_g": 160},
         "eaten": {"kcal": 1000, "protein_g": 80, "carbs_g": null, "fat_g": null, "meals": 2},
         "kcal_left": 1000, "protein_left_g": 80, "this_meal_kcal": 500,
         "options": [
           {"rank": 1, "id": "saved_meal:aaa", "source": "saved_meal", "name": "synthetic bowl",
            "kcal": 450, "protein_g": 45, "basis": "your saved estimate", "why": "10 g protein per 100 kcal",
            "estimate": true, "fits": true}
         ],
         "too_big_as_bought": [], "words": "It's 12:30, midday. About 1000 kcal left.",
         "week": {"days": 3, "mean_kcal": 1900}}
    """.trimIndent()

    @Test
    fun `the options travel with their ids and the rule to suggest only from them`() {
        val wire = bodyCoachJson.decodeFromString(MealOptionsWire.serializer(), body)
        val out = CoachTwoText.mealOptionsResult(FinanceRead.Fresh(wire))
        assertTrue(out.getBoolean("success"))
        assertTrue(out.getBoolean("estimate"))
        assertEquals(1000, out.getInt("kcal_left"))
        val first = out.getJSONArray("options").getJSONObject(0)
        assertEquals("saved_meal:aaa", first.getString("option_id"))
        assertEquals(450, first.getInt("kcal"))
        val rule = out.getString("rule")
        assertTrue(rule.contains("Suggest only from them"))
        assertTrue(rule.contains("log_meal_option"))
        assertTrue(rule.contains("Never pass a figure"))
    }

    @Test
    fun `nothing read says so and offers no figure`() {
        val out = CoachTwoText.mealOptionsResult(FinanceRead.Unavailable("The engine is unreachable."))
        assertFalse(out.getBoolean("success"))
        assertTrue(out.getString("message").contains("Nothing is known about what is left today"))
        assertFalse(out.has("options"))
    }

    @Test
    fun `alternatives say which are ruled out and how a change is proposed`() {
        val wire = bodyCoachJson.decodeFromString(
            AlternativesWire.serializer(),
            """{"exercise": "Bench press", "words": "Bench press can be swapped for Dumbbell bench press.",
               "options": [{"name": "Dumbbell bench press", "equipment": ["dumbbell"], "joints": [],
                            "allowed": true, "why_not": null, "progress_carries": false},
                           {"name": "Machine chest press", "equipment": ["machine"], "joints": [],
                            "allowed": false, "why_not": "needs a machine or cable", "progress_carries": false}]}""",
        )
        val out = CoachTwoText.alternativesResult(FinanceRead.Fresh(wire))
        val options = out.getJSONArray("options")
        assertEquals(2, options.length())
        assertFalse(options.getJSONObject(1).getBoolean("allowed"))
        assertTrue(out.getString("rule").contains("propose_programme_change"))
    }

    @Test
    fun `the proposal line is the first sentence of the engine's words`() {
        val p = ProgrammeProposalWire(
            id = "p1",
            words = "Proposed: 4 sessions a week, upper / lower. Out: Overhead press, loads the shoulder. Nothing changes until you accept.",
            afterSummary = "4 sessions a week, upper / lower",
        )
        assertEquals("Proposed: 4 sessions a week, upper / lower.", CoachTwoText.proposalLine(p))
        assertEquals("Plan change: 2 sessions a week, full body.", CoachTwoText.proposalLine(ProgrammeProposalWire("p2", "", "2 sessions a week, full body")))
    }

    @Test
    fun `a session with no date from a flexible-days engine is its place in the rotation, no comma`() {
        val today = java.time.LocalDate.of(2026, 11, 6)
        assertEquals("Upper 2", WorkoutText.session(UpcomingWire(dayKey = "C", dayLabel = "Upper 2", date = ""), today))
        assertEquals(
            "Day A, the next one, due now",
            WorkoutText.session(UpcomingWire(dayKey = "A", date = "", isToday = true), today),
        )
    }

    @Test
    fun `the draft line names the plan`() {
        val d = PlanDraftLineWire("d1", words = "Draft plan: x. y.", summary = "about 2000 kcal and 160 g protein a day")
        assertEquals("Lock in your plan: about 2000 kcal and 160 g protein a day.", CoachTwoText.draftLine(d))
    }
}
