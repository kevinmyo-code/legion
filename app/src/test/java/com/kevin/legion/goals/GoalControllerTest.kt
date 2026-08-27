package com.kevin.legion.goals

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises [GoalController] - the one write/read path ticket 19 requires so the `set_goal`/
 * `list_goals`/`close_goal` Live tools and the GOALS panel cannot drift apart. Robolectric through
 * the real [CarDatabase.getDatabase] path, same shape as `GoalDaoTest` - see [RoomTestReset]'s doc
 * comment for why the singleton needs resetting per method.
 */
@RunWith(RobolectricTestRunner::class)
class GoalControllerTest {
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
    fun `a prose-only goal round-trips with no number`() = runBlocking {
        val outcome = GoalController.setGoal(context, aspect = "log", statement = "ship the deck")
        val created = outcome as GoalController.SetOutcome.Created
        assertNull("a prose goal carries no target value", created.goal.targetValue)
        assertNull(created.goal.unit)
        assertNull(created.goal.metricKey)

        val current = GoalController.currentGoals(context, "log")
        assertEquals(1, current.size)
        assertEquals("ship the deck", current.single().statement)
    }

    @Test
    fun `a measurable goal round-trips its number and unit`() = runBlocking {
        val outcome = GoalController.setGoal(
            context, aspect = "bio", statement = "get to 175 lbs",
            targetValue = 175.0, unit = "lbs", metricKey = "bodyweight_kg",
        )
        val created = outcome as GoalController.SetOutcome.Created
        assertEquals(175.0, created.goal.targetValue)
        assertEquals("lbs", created.goal.unit)
        assertEquals("bodyweight_kg", created.goal.metricKey)
    }

    @Test
    fun `restating a goal with the same metricKey revises it, never mutates in place`() = runBlocking {
        val first = GoalController.setGoal(
            context, aspect = "cred", statement = "save 30k by 2028",
            targetValue = 30000.0, unit = "usd", metricKey = "savings_balance_cents",
        ) as GoalController.SetOutcome.Created

        val second = GoalController.setGoal(
            context, aspect = "cred", statement = "save 40k by 2030",
            targetValue = 40000.0, unit = "usd", metricKey = "savings_balance_cents",
        )

        val revised = second as GoalController.SetOutcome.Revised
        assertEquals("the revision shares the prior row's lineage", first.goal.lineageId, revised.goal.lineageId)
        assertEquals("the revision points back at the row it replaces", first.goal.id, revised.goal.supersedesId)
        assertTrue("a fresh row was inserted, not an update", revised.goal.id != first.goal.id)

        val current = GoalController.currentGoals(context, "cred")
        assertEquals("only the newest revision reads back as current", 1, current.size)
        assertEquals("save 40k by 2030", current.single().statement)

        val trail = CarDatabase.getDatabase(context).goalDao().history(first.goal.lineageId)
        assertEquals("nothing in the trail is deleted or overwritten", 2, trail.size)
    }

    @Test
    fun `saving with nothing materially changed is a no-op`() = runBlocking {
        val first = GoalController.setGoal(
            context, aspect = "fleet", statement = "sell the old car", targetValue = null, unit = null,
        ) as GoalController.SetOutcome.Created

        val resaved = GoalController.setGoal(
            context, aspect = "fleet", statement = "sell the old car", targetValue = null, unit = null,
            revises = first.goal,
        )

        assertTrue(resaved is GoalController.SetOutcome.Unchanged)
        val trail = CarDatabase.getDatabase(context).goalDao().history(first.goal.lineageId)
        assertEquals("no revision row was written", 1, trail.size)
    }

    @Test
    fun `editing the explicit revises row never depends on metricKey matching`() = runBlocking {
        val first = GoalController.setGoal(
            context, aspect = "bio", statement = "run a 5k", targetValue = null, unit = null,
        ) as GoalController.SetOutcome.Created

        val revised = GoalController.setGoal(
            context, aspect = "bio", statement = "run a 10k",
            revises = first.goal,
        ) as GoalController.SetOutcome.Revised

        assertEquals(first.goal.lineageId, revised.goal.lineageId)
        assertEquals("run a 10k", GoalController.currentGoals(context, "bio").single().statement)
    }

    @Test
    fun `close excludes a goal from currentGoals`() = runBlocking {
        val created = GoalController.setGoal(context, aspect = "log", statement = "clear the inbox") as GoalController.SetOutcome.Created
        GoalController.closeByLineage(context, created.goal.lineageId, "achieved")

        assertTrue(GoalController.currentGoals(context, "log").isEmpty())
        val trail = CarDatabase.getDatabase(context).goalDao().history(created.goal.lineageId)
        assertEquals("achieved", trail.single().status)
    }

    @Test
    fun `closeGoal by spoken text matches a unique current goal`() = runBlocking {
        GoalController.setGoal(context, aspect = "cred", statement = "save for a down payment")
        val outcome = GoalController.closeGoal(context, "cred", "down payment")

        assertTrue(outcome is GoalController.CloseOutcome.Closed)
        assertTrue(GoalController.currentGoals(context, "cred").isEmpty())
    }

    @Test
    fun `closeGoal by spoken text reports NotFound rather than guessing`() = runBlocking {
        GoalController.setGoal(context, aspect = "cred", statement = "save for a down payment")
        val outcome = GoalController.closeGoal(context, "cred", "buy a boat")

        assertTrue(outcome is GoalController.CloseOutcome.NotFound)
        assertEquals(1, GoalController.currentGoals(context, "cred").size)
    }

    @Test
    fun `closeGoal by spoken text reports Ambiguous rather than picking one`() = runBlocking {
        GoalController.setGoal(context, aspect = "cred", statement = "save for a house")
        GoalController.setGoal(context, aspect = "cred", statement = "save for a car")
        val outcome = GoalController.closeGoal(context, "cred", "save")

        val ambiguous = outcome as GoalController.CloseOutcome.Ambiguous
        assertEquals(2, ambiguous.matches.size)
        assertEquals("neither candidate goal was touched", 2, GoalController.currentGoals(context, "cred").size)
    }

    @Test
    fun `allCurrentGoals reads across every aspect`() = runBlocking {
        GoalController.setGoal(context, aspect = "bio", statement = "bio goal")
        GoalController.setGoal(context, aspect = "fleet", statement = "fleet goal")

        val all = GoalController.allCurrentGoals(context)
        assertEquals(2, all.size)
    }
}
