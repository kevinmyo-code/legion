package com.kevin.legion.advisor

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.goals.GoalController
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [GoalPlanAgent.acceptWholePlan] (ticket 07, `goal-plans`) - the "generate plan" button's one-tap
 * write, Robolectric-plus-Room the same shape as [com.kevin.legion.service.LiveToolboxGoalPlanTest]
 * and [GoalChecklistSyncTest]. No test here proposes a workout piece: [GoalPlan.pendingWorkoutGoal]
 * routes through [WorkoutPlanAgent], a real network call, exactly the same as
 * [com.kevin.legion.service.LiveToolboxGoalPlanTest] avoids for `generate_goal_plan` itself - a
 * plan with no workout piece is a normal, complete plan (see [GoalPlanAgent.accept]'s own doc
 * comment), so this file proves every OTHER write without needing a key on the test machine.
 */
@RunWith(RobolectricTestRunner::class)
class GoalPlanAgentAcceptWholePlanTest {
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


    @Test
    fun `applies meal target, sleep target, and goals from one plan, no workout piece`() = runBlocking {
        val plan = GoalPlan(
            rationale = "Starting point, worth revisiting.",
            mealTarget = GoalPlanMealTarget(caloriesKcal = 2300, proteinG = 180.0, carbsG = 220.0, fatG = 70.0),
            sleepTarget = GoalPlanSleepTarget(hours = 8.0),
            goals = listOf(GoalPlanGoal(aspect = "bio", statement = "get to 175 lbs", targetValue = 175.0, unit = "lbs")),
        )

        GoalPlanAgent().acceptWholePlan(context, plan)

        val db = CarDatabase.getDatabase(context)
        val meal = db.mealTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))
        assertNotNull(meal)
        assertEquals(2300, meal!!.caloriesKcal)
        assertEquals(180.0, meal.proteinG, 0.0)

        val sleep = db.sleepTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis()))
        assertNotNull(sleep)
        assertEquals(480, sleep!!.targetMinutes)

        val goals = GoalController.currentGoals(context, "bio")
        assertEquals(1, goals.size)
        assertEquals("get to 175 lbs", goals.single().statement)
    }

    @Test
    fun `a plan with only a refusal writes nothing and does not throw`() = runBlocking {
        val plan = GoalPlan(rationale = "Can't help with that one.", refusals = listOf("sleep target: needs a physician"))

        GoalPlanAgent().acceptWholePlan(context, plan)

        val db = CarDatabase.getDatabase(context)
        assertNull(db.mealTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis())))
        assertNull(db.sleepTargetDao().currentTarget(dayStartEpoch(System.currentTimeMillis())))
        assertTrue(GoalController.currentGoals(context, "bio").isEmpty())
    }

    @Test
    fun `materializes today's checklist as part of the same call`() = runBlocking {
        val plan = GoalPlan(
            rationale = "Starting point.",
            mealTarget = GoalPlanMealTarget(caloriesKcal = 2300, proteinG = 180.0, carbsG = 220.0, fatG = 70.0),
        )

        GoalPlanAgent().acceptWholePlan(context, plan)

        val items = GoalChecklistSync.currentItems(context)
        assertEquals(1, items.size)
        assertTrue(items.single().text.contains("2300 kcal"))
    }

    // --- parseDeadline: MM/dd/yyyy in, epoch millis out, never a thrown exception ----------------

    @Test
    fun `parseDeadline reads MM-dd-yyyy into device-zone epoch millis`() {
        val epoch = GoalPlanAgent.parseDeadline("12/01/2026")
        assertNotNull(epoch)
        val readBack = java.time.Instant.ofEpochMilli(epoch!!).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        assertEquals(java.time.LocalDate.of(2026, 12, 1), readBack)
    }

    @Test
    fun `parseDeadline returns null for blank, missing, or unparseable text - never throws`() {
        assertNull(GoalPlanAgent.parseDeadline(null))
        assertNull(GoalPlanAgent.parseDeadline(""))
        assertNull(GoalPlanAgent.parseDeadline("   "))
        assertNull(GoalPlanAgent.parseDeadline("not a date"))
        assertNull(GoalPlanAgent.parseDeadline("2026-12-01")) // wrong format on purpose
    }
}
