package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.bodycoach.BodyCoachText
import com.kevin.legion.bodycoach.EnergyPlanWire
import com.kevin.legion.bodycoach.EnergyProfileWire
import com.kevin.legion.bodycoach.EnergyWire
import com.kevin.legion.bodycoach.ProgrammeDayWire
import com.kevin.legion.bodycoach.TargetWire
import com.kevin.legion.bodycoach.UpcomingWire
import com.kevin.legion.bodycoach.WorkoutText
import com.kevin.legion.bodycoach.WorkoutTodayWire
import com.kevin.legion.finance.FinanceRead
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Kevin's first voice session with body coach 2 (A25, 2026-11-06), one test per bug. The phrases
 * are the session's own words; every number is synthetic. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class CoachVoiceFixesTest {

    // ------------------------------------------------- 1. "lock it in" is not set_meal_target

    @Test
    fun `lock it in with no number of its own refuses set_meal_target and names the plan's lock`() {
        val said = "Okay, lock lock it in. Lock that in."
        val refusal = CoachRouting.setMealTargetRefusal(said, 1777)
        assertNotNull(refusal)
        assertTrue(refusal!!.startsWith("Nothing was set."))
        assertTrue(refusal.contains("ask_engine") && refusal.contains("lock_body_plan"))
        for (phrase in listOf("lock that in", "Lock in the plan", "lock it", "yes, lock my plan in")) {
            assertTrue(phrase, CoachRouting.saysLockIn(phrase))
        }
    }

    @Test
    fun `a number the user says as his own still sets, lock word or not`() {
        assertNull(CoachRouting.setMealTargetRefusal("lock in 2,150 calories", 2150))
        assertNull(CoachRouting.setMealTargetRefusal("make it 2150 calories a day", 2150))
        assertNull(CoachRouting.setMealTargetRefusal(null, 2150))
        assertFalse(CoachRouting.saysLockIn("unlock the car"))
        assertFalse(CoachRouting.saysLockIn("block in the calendar"))
    }

    @Test
    fun `set_meal_target is declared for his own number only, with no carbs or fat`() {
        val decl = declaration("set_meal_target")
        val desc = decl.getString("description")
        assertTrue(desc.contains("own number") && desc.contains("lock_body_plan"))
        val props = decl.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("calories") && props.has("protein_g"))
        assertFalse("carbs and fat are not targets (Kevin's ruling)", props.has("carbs_g") || props.has("fat_g"))
        assertTrue(AskEngine.DESCRIPTION.contains("'lock it in'"))
    }

    @Test
    fun `a lock or an accepted proposal through ask_engine makes the phone pull its target`() {
        fun answer(tool: String, error: Boolean = false) = EngineToolAnswer(tool, error, null, null, 0)
        assertTrue(CoachRouting.movesTheMealTarget(answer("lock_body_plan")))
        assertTrue(CoachRouting.movesTheMealTarget(answer("accept_energy_proposal")))
        assertFalse(CoachRouting.movesTheMealTarget(answer("lock_body_plan", error = true)))
        assertFalse(CoachRouting.movesTheMealTarget(answer("get_body_plan")))
    }

    // ------------------------------------------------------------ 2. one number, the plan's

    @Test
    fun `the energy result carries a waiting draft and how to lock it`() {
        val e = EnergyWire(
            basis = "formula", maintenanceKcal = 2600, suggestedTargetKcal = 2100, proteinG = 160, targetRatePct = 0.5,
            plan = EnergyPlanWire(id = "p1", status = "draft", kcalTarget = 2100, proteinG = 160, ratePct = 0.5),
        )
        val out = BodyCoachText.energyResult(FinanceRead.Fresh(e))
        assertEquals(2100, out.getInt("suggested_target_kcal"))
        val plan = out.getJSONObject("plan")
        assertEquals(2100, plan.getInt("kcal_target"))
        assertEquals("draft, not locked in yet", plan.getString("status"))
        assertTrue(plan.getString("how_to_lock").contains("lock_body_plan"))
        assertTrue(plan.getString("how_to_lock").contains("Never set_meal_target"))
    }

    @Test
    fun `an older engine with no plan or profile sends neither, and nothing is invented`() {
        val out = BodyCoachText.energyResult(FinanceRead.Fresh(EnergyWire(basis = "formula", maintenanceKcal = 2600)))
        assertFalse(out.has("plan"))
        assertFalse(out.has("profile"))
    }

    // ------------------------------------------------------------- 4. stated facts vs profile

    @Test
    fun `the energy result says the profile it stands on and to ask before updating it`() {
        val e = EnergyWire(
            basis = "formula",
            maintenanceKcal = 2600,
            profile = EnergyProfileWire(
                heightCm = 175.3, heightFtIn = "5 ft 9 in", sex = "male",
                birthYear = 1985, ageAbout = 41, activityLevel = "light",
            ),
        )
        val out = BodyCoachText.energyResult(FinanceRead.Fresh(e))
        val held = out.getJSONObject("profile")
        assertEquals("5 ft 9 in (175.3 cm)", held.getString("height"))
        assertEquals(1985, held.getInt("birth_year"))
        val rule = out.getString("if_user_states_different_facts")
        assertTrue(rule.contains("ask whether to update it"))
        assertTrue(rule.contains("Update only after a yes"))
        assertTrue(rule.contains("set_body_profile") && rule.contains("read the estimate again"))
    }

    // --------------------------------------------------------------------- 5. no disclaimer

    @Test
    fun `every body answer carries the coach note, and it bans the routine disclaimer`() {
        val r = LiveToolbox.withCoachNote(AgentResult.Success("Results for the user's question ..."))
        val text = (r as AgentResult.Success).text
        assertTrue(text.endsWith(BodyCoachText.COACH_SAY))
        val say = BodyCoachText.COACH_SAY
        assertTrue(say.contains("\"estimate\"") && say.contains("no medical disclaimer"))
        for (kept in listOf("pain", "injury", "800 kcal", "disordered eating")) assertTrue(kept, say.contains(kept))
        assertEquals(AgentResult.Offline, LiveToolbox.withCoachNote(AgentResult.Offline))
    }

    // ------------------------------------------------------------- 6. workouts are ask_body's

    @Test
    fun `workout questions sent to ask_goals are the body route's`() {
        val asked = listOf(
            "how about my workouts?", "What are the workout sessions like?", "what's on Day B", "what's my programme",
        )
        for (q in asked) {
            assertTrue(q, CoachRouting.isWorkoutQuestion(q))
        }
        for (q in listOf("how am I doing on my goals?", "what's my training goal", "", null)) {
            assertFalse(q.toString(), CoachRouting.isWorkoutQuestion(q))
        }
        val goals = declaration("ask_goals").getString("description")
        assertTrue(goals.contains("Workouts, sessions, the programme") && goals.contains("go to ask_body"))
        assertTrue(declaration("ask_body").getString("description").contains("the programme (Day A/B, sessions)"))
    }

    // -------------------------------------------------------- 7. "what's on DA" is not the agenda

    @Test
    fun `Day A in any spoken form names the programme, and the agenda refuses it`() {
        for (t in listOf("No, what's on DA?", "what's on day A", "and day-B?", "what's on day eh", "D B please")) {
            assertTrue(t, CoachRouting.namesProgrammeDay(t))
            assertNotNull(t, CoachRouting.agendaRefusal(t))
        }
        assertTrue(CoachRouting.agendaRefusal("No, what's on DA?")!!.contains("get_workout_programme"))
        for (t in listOf("what's on today", "show me my agenda", "I train one day a week", "da igual", null)) {
            assertNull(t.toString(), CoachRouting.agendaRefusal(t))
        }
        assertNull("an agenda word wins", CoachRouting.agendaRefusal("my agenda for Day A"))
        assertTrue(declaration("show_agenda_modal").getString("description").contains("Never for Day A/B or 'DA'"))
    }

    // ------------------------------------------------------------ 8. see all the workouts

    @Test
    fun `see all workouts lists every day with its targets and the next sessions`() {
        fun t(name: String, weight: Double?, calibrating: Boolean = false) =
            TargetWire(
                exerciseKey = name, name = name, sets = 3, repsLow = 6, repsHigh = 8,
                weightLbs = weight, calibrating = calibrating,
            )
        val w = WorkoutTodayWire(
            days = listOf(
                ProgrammeDayWire(
                    dayKey = "A",
                    exercises = listOf(t("Bench press", 125.0), t("Split squat", null, calibrating = true)),
                ),
                ProgrammeDayWire(dayKey = "B", dayLabel = "Day B", exercises = listOf(t("Row", 95.0))),
            ),
            upcoming = listOf(
                UpcomingWire(dayKey = "A", date = "", isToday = true),
                UpcomingWire(dayKey = "B", dayLabel = "Day B", date = ""),
            ),
        )
        val view = WorkoutText.allWorkouts(w, LocalDate.of(2026, 1, 5))!!
        assertEquals(listOf("Day A", "Day B"), view.days.map { it.label })
        assertEquals("Bench press" to "3 x 6-8 @ 125 lb", view.days[0].rows[0])
        assertEquals("Split squat" to "3 x 6-8 @ your pick", view.days[0].rows[1])
        assertTrue(view.days[0].calibrating)
        assertFalse(view.days[1].calibrating)
        assertEquals(listOf("Day A, the next one, due now", "Day B"), view.next)
        assertNull("no days sent: no expander", WorkoutText.allWorkouts(WorkoutTodayWire(), LocalDate.of(2026, 1, 5)))
        assertNull(WorkoutText.allWorkouts(null, LocalDate.of(2026, 1, 5)))
    }

    private fun declaration(name: String): JSONObject {
        val all: JSONArray = LiveToolbox.declarations()
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            if (d.getString("name") == name) return d
        }
        throw AssertionError("$name is not declared")
    }
}
