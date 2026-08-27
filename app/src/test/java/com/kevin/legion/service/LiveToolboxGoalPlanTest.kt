package com.kevin.legion.service

import com.kevin.legion.advisor.GoalPlan
import com.kevin.legion.advisor.GoalPlanAgent
import com.kevin.legion.advisor.GoalPlanGoal
import com.kevin.legion.advisor.GoalPlanMealTarget
import com.kevin.legion.advisor.GoalPlanResult
import com.kevin.legion.advisor.GoalPlanSleepTarget
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionMemory
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `generate_goal_plan`/`accept_goal_plan` dispatch (ticket 03, `goal-plans`) - same Robolectric-
 * plus-Room shape as [LiveToolboxAdvisorTest]. No test here makes a real network call: no Gemini
 * key is ever configured, so [LiveToolbox.generateGoalPlanTool] always takes its own
 * "I need a Gemini key" refusal path AFTER persisting a stated constraint - deliberately, so the
 * ticket's core promise ("having said once that he has no gym, he should not have to say it
 * again") is provable deterministically, without depending on a real model response.
 * [LiveToolbox.mapGoalPlanResult] itself is covered separately, with no Context and no dispatch
 * at all, for the branches a missing key can't reach (Success, ParseFailed, refusals).
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxGoalPlanTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    // --- generate_goal_plan persists a stated constraint, independent of whether a plan could
    // actually be generated --------------------------------------------------------------------

    @Test
    fun `a new_constraint is persisted even when there is no Gemini key to generate with`() = runBlocking {
        val result = LiveToolbox.dispatch(
            context, "generate_goal_plan",
            JSONObject().put("goal_text", "lose fat, gain muscle").put("new_constraint", "no gym access"),
        )!!

        // Deliberately NOT asserted: whether the generation itself succeeded. That depends on
        // whether the machine running this test happens to have a Gemini key in its
        // local.properties, which is a property of the developer's setup and not of the code.
        // An earlier cut asserted the call must FAIL "because there is no key on file" and duly
        // passed on a keyless machine and failed on Kevin's. What this test is actually about is
        // the sentence below it.

        val stored = CarDatabase.getDatabase(context).companionMemoryDao()
            .byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)
        assertEquals(
            "the stated constraint must survive even though nothing could be generated from it",
            1, stored.size,
        )
        assertTrue(stored.first().text.contains("no gym access"))
        assertEquals(
            "written directly from this turn, never from a later consolidation pass",
            CompanionMemory.Source.STATED, stored.first().source,
        )
    }

    @Test
    fun `a constraint stated once is not duplicated by a later call restating it`() = runBlocking {
        LiveToolbox.dispatch(
            context, "generate_goal_plan",
            JSONObject().put("goal_text", "lose fat, gain muscle").put("new_constraint", "no gym access"),
        )
        // Restated with different casing/whitespace, the way a second exchange plausibly would.
        LiveToolbox.dispatch(
            context, "generate_goal_plan",
            JSONObject().put("goal_text", "lose fat, gain muscle").put("new_constraint", "  No Gym Access  "),
        )

        val stored = CarDatabase.getDatabase(context).companionMemoryDao()
            .byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)
        assertEquals("restating an already-known constraint must not grow the recall pool", 1, stored.size)
    }

    @Test
    fun `a call with no new_constraint writes nothing, only reads what is already on file`() = runBlocking {
        LiveToolbox.dispatch(context, "generate_goal_plan", JSONObject().put("goal_text", "lose fat, gain muscle"))

        val stored = CarDatabase.getDatabase(context).companionMemoryDao()
            .byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)
        assertTrue("no new_constraint given, so nothing should be written", stored.isEmpty())
    }

    @Test
    fun `a blank goal_text is refused before anything is persisted`() = runBlocking {
        val result = LiveToolbox.dispatch(
            context, "generate_goal_plan",
            JSONObject().put("goal_text", "  ").put("new_constraint", "no gym access"),
        )!!

        assertFalse(result.getBoolean("success"))
        val stored = CarDatabase.getDatabase(context).companionMemoryDao()
            .byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)
        assertTrue(
            "a call with no real goal must not persist a constraint either - there is nothing to " +
                "attach it to",
            stored.isEmpty(),
        )
    }

    // --- A constraint already on file survives to the NEXT call's combined goal text, across
    // sessions - the mechanism GoalPlanAgent.withConstraints and this dispatch build together ----

    @Test
    fun `a constraint stated in one call is folded into the goal text of a LATER, separate call`() = runBlocking {
        LiveToolbox.dispatch(
            context, "generate_goal_plan",
            JSONObject().put("goal_text", "lose fat, gain muscle").put("new_constraint", "no gym access"),
        )

        // A brand-new call - the model restating only the original goal, as its own declaration
        // instructs, with no new_constraint of its own. This is the exact "he should not have to
        // say it again" case.
        val storedNextTime = CarDatabase.getDatabase(context).companionMemoryDao()
            .byCategoryPrefixed(CompanionMemory.Category.DRIVER, GoalPlanAgent.CONSTRAINT_PREFIX)
            .map { it.text.removePrefix(GoalPlanAgent.CONSTRAINT_PREFIX).trim() }
        val combined = GoalPlanAgent.withConstraints("lose fat, gain muscle", storedNextTime)

        assertTrue(combined.contains("lose fat, gain muscle"))
        assertTrue("the earlier constraint must reach the new call's prompt without being restated", combined.contains("no gym access"))
    }

    // --- accept_goal_plan -----------------------------------------------------------------------

    @Test
    fun `accept_goal_plan with no workout_goal reports success and touches nothing`() = runBlocking {
        val result = LiveToolbox.dispatch(context, "accept_goal_plan", JSONObject())!!

        assertTrue("a plan with no workout piece is a complete plan, not an error", result.getBoolean("success"))
        assertTrue(result.getString("message").contains("no workout piece"))
    }

    @Test
    fun `accept_goal_plan always answers in a shape the model can speak from`() = runBlocking {
        // Whether this reaches the network depends on the running machine's local.properties, so
        // the OUTCOME is not asserted - only that both outcomes are well formed. On failure the
        // message must say in words what did NOT happen, which is what CLAUDE.md §7's outcome-verb
        // rule stands on: the assistant may only claim it built a plan after a tool result says so.
        val result = LiveToolbox.dispatch(
            context, "accept_goal_plan",
            JSONObject().put("workout_goal", "Build strength three days a week, kettlebells only."),
        )!!

        assertTrue("every tool result carries both fields", result.has("success") && result.has("message"))
        if (!result.getBoolean("success")) {
            assertTrue(
                "a failure must name what did not happen, never return an empty message",
                result.getString("message").contains("I couldn't"),
            )
        }
    }

    // --- mapGoalPlanResult: no Context, no dispatch, every branch reachable ---------------------

    @Test
    fun `mapGoalPlanResult serialises every present field of a successful plan`() {
        val plan = GoalPlan(
            rationale = "Starting at 2300 calories - worth revisiting once you have a couple of weeks of weight data.",
            mealTarget = GoalPlanMealTarget(caloriesKcal = 2300, proteinG = 180.0, carbsG = 220.0, fatG = 70.0),
            sleepTarget = GoalPlanSleepTarget(hours = 8.0),
            pendingWorkoutGoal = "Build strength three days a week, kettlebells only.",
            goals = listOf(GoalPlanGoal(aspect = "bio", statement = "get to 175 lbs", targetValue = 175.0, unit = "lbs")),
            refusals = emptyList(),
        )

        val response = LiveToolbox.mapGoalPlanResult(GoalPlanResult.Success(plan))

        assertTrue(response.getBoolean("success"))
        assertTrue(response.getString("message").contains("worth revisiting"))
        assertEquals(2300, response.getJSONObject("mealTarget").getInt("caloriesKcal"))
        assertEquals(8.0, response.getJSONObject("sleepTarget").getDouble("hours"), 0.0)
        assertEquals("Build strength three days a week, kettlebells only.", response.getString("workoutGoal"))
        assertEquals(1, response.getJSONArray("goals").length())
        assertEquals("get to 175 lbs", response.getJSONArray("goals").getJSONObject(0).getString("statement"))
        assertFalse("no refusals on this plan, so the key must be absent entirely", response.has("refusals"))
    }

    @Test
    fun `mapGoalPlanResult carries refusals through so the model can speak them`() {
        val plan = GoalPlan(
            rationale = "Sleep target refused - keeping the rest of the plan.",
            refusals = listOf("sleep target: the goal names a diagnosed condition, which needs a physician."),
        )

        val response = LiveToolbox.mapGoalPlanResult(GoalPlanResult.Success(plan))

        assertTrue(response.getBoolean("success"))
        assertEquals(1, response.getJSONArray("refusals").length())
        assertTrue(response.getJSONArray("refusals").getString(0).contains("physician"))
    }

    @Test
    fun `mapGoalPlanResult on ParseFailed still relays the model's prose rather than discarding it`() {
        val prose = "Roughly 2200 calories and 8 hours of sleep, kettlebell strength work three times a week."
        val response = LiveToolbox.mapGoalPlanResult(GoalPlanResult.ParseFailed(prose))

        assertTrue(response.getBoolean("success"))
        assertTrue(response.getString("message").contains(prose))
        assertTrue(
            "must say plainly there is nothing to accept yet",
            response.getString("message").contains("nothing to accept"),
        )
    }

    @Test
    fun `mapGoalPlanResult on Failed refuses in words`() {
        val response = LiveToolbox.mapGoalPlanResult(GoalPlanResult.Failed)
        assertFalse(response.getBoolean("success"))
        assertTrue(response.getString("message").isNotBlank())
    }
}
