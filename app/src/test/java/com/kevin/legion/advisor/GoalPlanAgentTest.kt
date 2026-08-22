package com.kevin.legion.advisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage of [GoalPlanAgent]: [GoalPlanAgent.parse]'s gate (including the one
 * boundary it enforces in Kotlin rather than trusting prose - [GoalPlanAgent.HARD_FLOOR_CALORIES_KCAL]),
 * [GoalPlanAgent.responseSchema]'s shape, and the honesty/refusal contract composed into
 * [GoalPlanAgent.SYSTEM_INSTRUCTION]. No Context, no Room, no network - the same "split for
 * unit-testability" pattern [AdvisorAgentTest] and [AdvisorAnswerTest] already use.
 */
class GoalPlanAgentTest {

    // --- composeContext ---------------------------------------------------------------------

    @Test
    fun `composeContext wraps the combined doctrine under one PLAYBOOK header`() {
        val composed = GoalPlanAgent.composeContext("BIO:\nsome bio text\n\nPLAN:\nsome plan text")
        assertTrue(composed.startsWith("PLAYBOOK:\n"))
        assertTrue(composed.contains("BIO:"))
        assertTrue(composed.contains("PLAN:"))
    }

    // --- parse: the happy path ---------------------------------------------------------------

    @Test
    fun `parses a full response with every field present`() {
        val raw = """
            {
              "rationale": "Starting at 2300 calories and 180g protein - worth revisiting once you have a couple of weeks of weight data.",
              "mealTarget": {"caloriesKcal": 2300, "proteinG": 180.0, "carbsG": 220.0, "fatG": 70.0},
              "sleepTarget": {"hours": 8.0},
              "workoutGoal": "Build strength three days a week, kettlebells only.",
              "goals": [
                {"aspect": "bio", "statement": "get to 175 lbs", "targetValue": 175.0, "unit": "lbs", "metricKey": "bodyweight_lbs", "deadline": "12/01/2026"}
              ],
              "refusals": []
            }
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)

        assertNotNull(plan)
        assertTrue(plan!!.rationale.contains("worth revisiting"))
        assertEquals(2300, plan.mealTarget!!.caloriesKcal)
        assertEquals(180.0, plan.mealTarget.proteinG, 0.0)
        assertEquals(8.0, plan.sleepTarget!!.hours, 0.0)
        assertEquals("Build strength three days a week, kettlebells only.", plan.pendingWorkoutGoal)
        assertNull("workoutPlanMessage is only ever filled in by accept(), never by parse()", plan.workoutPlanMessage)
        assertEquals(1, plan.goals.size)
        assertEquals("bio", plan.goals[0].aspect)
        assertEquals("12/01/2026", plan.goals[0].deadline)
        assertTrue(plan.refusals.isEmpty())
    }

    @Test
    fun `a plan naming only a refusal, with every target omitted, is still a valid plan`() {
        val raw = """
            {
              "rationale": "This goal describes a medical condition, so I'm not proposing a target here.",
              "refusals": ["meal target: the goal names diagnosed kidney disease, which needs a physician, not this app."]
            }
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)

        assertNotNull(plan)
        assertNull(plan!!.mealTarget)
        assertNull(plan.sleepTarget)
        assertNull(plan.pendingWorkoutGoal)
        assertTrue(plan.goals.isEmpty())
        assertEquals(1, plan.refusals.size)
        assertTrue(plan.refusals[0].contains("kidney disease"))
    }

    @Test
    fun `strips a json-tagged markdown fence before parsing, reusing AdvisorAnswer's stripper`() {
        val raw = "```json\n{\"rationale\": \"fenced\", \"sleepTarget\": {\"hours\": 7.5}}\n```"
        val plan = GoalPlanAgent.parse(raw)
        assertNotNull(plan)
        assertEquals("fenced", plan!!.rationale)
        assertEquals(7.5, plan.sleepTarget!!.hours, 0.0)
    }

    // --- parse: clean failures, never a partial accept ----------------------------------------

    @Test
    fun `missing rationale fails to parse rather than defaulting`() {
        val raw = """{"sleepTarget": {"hours": 8.0}}"""
        assertNull(GoalPlanAgent.parse(raw))
    }

    @Test
    fun `blank rationale fails to parse`() {
        assertNull(GoalPlanAgent.parse("""{"rationale": "   ", "sleepTarget": {"hours": 8.0}}"""))
    }

    @Test
    fun `malformed json fails to parse`() {
        assertNull(GoalPlanAgent.parse("not json at all"))
    }

    @Test
    fun `a plan with rationale but every other field absent fails - proposing nothing is not a plan`() {
        assertNull(GoalPlanAgent.parse("""{"rationale": "Nothing to propose here."}"""))
    }

    @Test
    fun `a mealTarget missing one of its four required numbers fails the whole parse`() {
        val raw = """
            {"rationale": "x", "mealTarget": {"caloriesKcal": 2200, "proteinG": 150.0, "carbsG": 200.0}}
        """.trimIndent()
        assertNull(GoalPlanAgent.parse(raw))
    }

    @Test
    fun `a sleepTarget missing hours fails the whole parse`() {
        val raw = """{"rationale": "x", "sleepTarget": {}}"""
        assertNull(GoalPlanAgent.parse(raw))
    }

    @Test
    fun `a goal entry missing statement fails the whole parse`() {
        val raw = """
            {"rationale": "x", "goals": [{"aspect": "bio"}]}
        """.trimIndent()
        assertNull(GoalPlanAgent.parse(raw))
    }

    @Test
    fun `a goal entry naming an aspect other than bio fails the whole parse - BIO-only for now`() {
        val raw = """
            {"rationale": "x", "goals": [{"aspect": "cred", "statement": "save 30k"}]}
        """.trimIndent()
        assertNull(GoalPlanAgent.parse(raw))
    }

    // --- parse: the one boundary enforced in Kotlin, not prose ---------------------------------

    @Test
    fun `a meal target at exactly the hard floor is refused, not accepted`() {
        val raw = """
            {
              "rationale": "x",
              "mealTarget": {"caloriesKcal": 800, "proteinG": 150.0, "carbsG": 100.0, "fatG": 50.0},
              "sleepTarget": {"hours": 8.0}
            }
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)

        assertNotNull("the sleep target alone keeps this a valid plan", plan)
        assertNull("800 kcal/day is refused - at or below the floor", plan!!.mealTarget)
        assertEquals(1, plan.refusals.size)
        assertTrue(plan.refusals[0].contains("800"))
    }

    @Test
    fun `a meal target well below the hard floor is refused the same way`() {
        val raw = """
            {"rationale": "x", "mealTarget": {"caloriesKcal": 500, "proteinG": 120.0, "carbsG": 80.0, "fatG": 40.0}}
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)

        assertNotNull("the code-added refusal note alone keeps this a valid plan", plan)
        assertNull(plan!!.mealTarget)
        assertEquals(1, plan.refusals.size)
    }

    @Test
    fun `the floor refuses even when the model never flagged it itself`() {
        // The model returned no "refusals" entry at all for this target - the Kotlin floor still
        // catches it, unconditionally, regardless of what the prompt asked for.
        val raw = """
            {"rationale": "x", "mealTarget": {"caloriesKcal": 700, "proteinG": 100.0, "carbsG": 60.0, "fatG": 30.0}, "refusals": []}
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)
        assertNotNull(plan)
        assertNull(plan!!.mealTarget)
        assertEquals(1, plan.refusals.size)
    }

    @Test
    fun `a meal target just above the hard floor is accepted, not refused`() {
        val raw = """
            {"rationale": "x", "mealTarget": {"caloriesKcal": 801, "proteinG": 150.0, "carbsG": 100.0, "fatG": 50.0}}
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)

        assertNotNull(plan)
        assertEquals(801, plan!!.mealTarget!!.caloriesKcal)
        assertTrue(plan.refusals.isEmpty())
    }

    @Test
    fun `the hard floor constant matches the doctrine's own medically-supervised threshold`() {
        assertEquals(800, GoalPlanAgent.HARD_FLOOR_CALORIES_KCAL)
    }

    // --- responseSchema -----------------------------------------------------------------------

    @Test
    fun `responseSchema declares OBJECT type with only rationale required`() {
        val schema = GoalPlanAgent.responseSchema()
        assertEquals("OBJECT", schema.getString("type"))
        val required = schema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("rationale", required.getString(0))
    }

    @Test
    fun `responseSchema types every leaf with an uppercase Gemini Type name`() {
        val schema = GoalPlanAgent.responseSchema()
        val props = schema.getJSONObject("properties")
        assertEquals("STRING", props.getJSONObject("rationale").getString("type"))
        assertEquals("OBJECT", props.getJSONObject("mealTarget").getString("type"))
        assertEquals("OBJECT", props.getJSONObject("sleepTarget").getString("type"))
        assertEquals("STRING", props.getJSONObject("workoutGoal").getString("type"))
        assertEquals("ARRAY", props.getJSONObject("goals").getString("type"))
        assertEquals("ARRAY", props.getJSONObject("refusals").getString("type"))
    }

    @Test
    fun `responseSchema pins goals aspect to bio only - BIO-only for now`() {
        val schema = GoalPlanAgent.responseSchema()
        val aspectEnum = schema.getJSONObject("properties")
            .getJSONObject("goals").getJSONObject("items")
            .getJSONObject("properties").getJSONObject("aspect")
            .getJSONArray("enum")
        assertEquals(1, aspectEnum.length())
        assertEquals("bio", aspectEnum.getString(0))
    }

    @Test
    fun `responseSchema marks mealTarget, sleepTarget, workoutGoal nullable and not required`() {
        val schema = GoalPlanAgent.responseSchema()
        val props = schema.getJSONObject("properties")
        assertTrue(props.getJSONObject("mealTarget").getBoolean("nullable"))
        assertTrue(props.getJSONObject("sleepTarget").getBoolean("nullable"))
        assertTrue(props.getJSONObject("workoutGoal").getBoolean("nullable"))

        val required = schema.getJSONArray("required")
        val requiredNames = (0 until required.length()).map { required.getString(it) }
        assertFalse(requiredNames.contains("mealTarget"))
        assertFalse(requiredNames.contains("sleepTarget"))
        assertFalse(requiredNames.contains("workoutGoal"))
        assertFalse(requiredNames.contains("goals"))
        assertFalse(requiredNames.contains("refusals"))
    }

    @Test
    fun `responseSchema builds a fresh JSONObject per call, not a shared mutable instance`() {
        val first = GoalPlanAgent.responseSchema()
        val second = GoalPlanAgent.responseSchema()
        assertTrue(first !== second)
        assertEquals(first.toString(), second.toString())
    }

    // --- the honesty clause and the refusal contract, composed into the system instruction -----

    @Test
    fun `SYSTEM_INSTRUCTION states the starting-point honesty rule and says it applies once`() {
        assertTrue(GoalPlanAgent.SYSTEM_INSTRUCTION.contains("HONESTY"))
        assertTrue(GoalPlanAgent.SYSTEM_INSTRUCTION.contains("starting point"))
        assertTrue(GoalPlanAgent.SYSTEM_INSTRUCTION.contains("ONCE"))
        assertTrue(GoalPlanAgent.SYSTEM_INSTRUCTION.contains("worth revisiting"))
    }

    @Test
    fun `SYSTEM_INSTRUCTION forbids phrasing the hedge as a commitment the app performs`() {
        // Settled decision 8: invite, never promise. The instruction must name the forbidden
        // commitment phrasing explicitly, not just gesture at "be honest".
        assertTrue(
            GoalPlanAgent.SYSTEM_INSTRUCTION.contains("Never phrase the hedge as a promise"),
        )
    }

    @Test
    fun `SYSTEM_INSTRUCTION states the per-target refusal contract, never fail the whole plan`() {
        // Whitespace-normalized (the source wraps prose across lines for readability) so this
        // assertion is not coupled to exactly where a line break happens to fall.
        val flattened = GoalPlanAgent.SYSTEM_INSTRUCTION.replace(Regex("\\s+"), " ")
        assertTrue(flattened.contains("REFUSAL"))
        assertTrue(flattened.contains("refuse that target ONLY", ignoreCase = false))
        assertTrue(flattened.contains("Never fail the whole plan"))
    }

    @Test
    fun `SYSTEM_INSTRUCTION forbids a body-fat input anywhere in the path`() {
        assertTrue(
            GoalPlanAgent.SYSTEM_INSTRUCTION.contains("Never ask for or estimate body fat"),
        )
    }

    @Test
    fun `SYSTEM_INSTRUCTION embeds the RESPONSE_SCHEMA prose contract`() {
        assertTrue(GoalPlanAgent.SYSTEM_INSTRUCTION.contains(GoalPlanAgent.RESPONSE_SCHEMA.trim()))
    }

    // --- withConstraints (ticket 03, `goal-plans`: "he should not have to say it again") --------

    @Test
    fun `withConstraints returns goalText byte-for-byte unchanged when nothing is stored`() {
        assertEquals(
            "lose fat, gain muscle",
            GoalPlanAgent.withConstraints("lose fat, gain muscle", emptyList()),
        )
    }

    @Test
    fun `withConstraints folds every stored constraint into the goal text`() {
        val combined = GoalPlanAgent.withConstraints(
            "lose fat, gain muscle",
            listOf("no gym access", "only free on weeknights after 8pm"),
        )
        assertTrue(combined.contains("lose fat, gain muscle"))
        assertTrue(combined.contains("no gym access"))
        assertTrue(combined.contains("only free on weeknights after 8pm"))
    }

    @Test
    fun `withConstraints preserves the order constraints were stated in`() {
        val combined = GoalPlanAgent.withConstraints("x", listOf("first stated", "second stated"))
        assertTrue(combined.indexOf("first stated") < combined.indexOf("second stated"))
    }

    @Test
    fun `the constraint marker never leaks into the shared instruction or schema`() {
        // GoalPlanAgent.CONSTRAINT_PREFIX is an internal storage/filter marker
        // (service/LiveToolbox.kt strips it before anything reaches this class) - it must never
        // appear in text the model actually reads.
        assertFalse(GoalPlanAgent.SYSTEM_INSTRUCTION.contains(GoalPlanAgent.CONSTRAINT_PREFIX))
    }

    // --- a revision that contradicts the doctrine refuses ONLY that field, never the rest of the
    // SAME regenerated plan (ticket 03 settled call 3: "keep the existing plan, refuse that one
    // change") - ticket 03 stores no plan to diff against, so "the existing plan" is the other
    // fields the SAME model response still carries when only one target crosses a boundary. -----

    @Test
    fun `a revision asking to cut below the hard floor is refused alone - the sleep and workout pieces from the same response survive`() {
        val raw = """
            {
              "rationale": "700 kcal/day is below what I can propose - keeping the rest of your plan as it was.",
              "mealTarget": {"caloriesKcal": 700, "proteinG": 150.0, "carbsG": 60.0, "fatG": 40.0},
              "sleepTarget": {"hours": 8.0},
              "workoutGoal": "Build strength three days a week, kettlebells only."
            }
        """.trimIndent()

        val plan = GoalPlanAgent.parse(raw)

        assertNotNull("a revision refusing one target must still be a valid plan", plan)
        assertNull("700 kcal/day is refused on its own", plan!!.mealTarget)
        assertEquals("the sleep target from the same revision survives untouched", 8.0, plan.sleepTarget!!.hours, 0.0)
        assertEquals(
            "the workout piece from the same revision survives untouched",
            "Build strength three days a week, kettlebells only.",
            plan.pendingWorkoutGoal,
        )
        assertEquals(1, plan.refusals.size)
        assertTrue(plan.refusals[0].contains("700"))
    }
}
