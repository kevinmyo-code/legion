package com.kevin.legion.advisor

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.MealTarget
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.notes.NotesController
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
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
 * [GoalChecklistSync] (ticket 06, `goal-plans`) - Robolectric-plus-Room, same shape as
 * [com.kevin.legion.service.LiveToolboxGoalPlanTest], because [GoalChecklistSync.materializeToday]
 * touches Room through [NotesController] and this domain's own reconciliation ([GoalChecklistTest])
 * is already covered pure and separately.
 *
 * Three things this ticket's own verification names explicitly, each with its own test below:
 * idempotence across repeated same-day runs, that a materialized line is a genuinely tickable
 * one-off item (not refused by [NotesController.tick]'s recurring-item guard), and that the
 * retention trim removes ticked and un-ticked plan items together.
 */
@RunWith(RobolectricTestRunner::class)
class GoalChecklistSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun givenAMealTarget(now: Long) {
        CarDatabase.getDatabase(context).mealTargetDao().upsert(
            MealTarget(
                caloriesKcal = 2300, proteinG = 180.0, carbsG = 220.0, fatG = 70.0,
                effectiveFromDateEpoch = dayStartEpoch(now), updatedAt = now,
            ),
        )
    }

    /** Every non-deleted [ListItem] this object has ever written, on the one list. */
    private suspend fun planItems(): List<ListItem> {
        val list = NotesController.theList(context)
        return NotesController.allItems(context).filter { it.listId == list.id && it.text.startsWith(GoalChecklistSync.ITEM_PREFIX) }
    }

    // --- idempotence: opening the app five times in one day must not produce five copies --------

    @Test
    fun `materializing the same day repeatedly does not duplicate the line`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)

        // Five calls, same as five app opens in one day - the ticket's own worded scenario.
        repeat(5) { GoalChecklistSync.materializeToday(context, now) }

        val items = planItems()
        assertEquals("one call's worth of lines, not five", 1, items.size)
        assertTrue(items.single().text.contains("2300 kcal"))
    }

    @Test
    fun `materializing again after a target changes replaces the old line, still once`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)

        // The target changes mid-day - a later acceptance writes a new MealTarget row, and a
        // second materialize call in the SAME day's window should retire the stale line rather
        // than pile a second one alongside it.
        CarDatabase.getDatabase(context).mealTargetDao().upsert(
            MealTarget(
                caloriesKcal = 2500, proteinG = 190.0, carbsG = 230.0, fatG = 75.0,
                effectiveFromDateEpoch = dayStartEpoch(now), updatedAt = now + 1,
            ),
        )
        GoalChecklistSync.materializeToday(context, now)

        val items = planItems()
        assertEquals(1, items.size)
        assertTrue("the superseded 2300 line must be gone, not left stale", items.single().text.contains("2500 kcal"))
    }

    // --- a materialized plan line is genuinely tickable, not refused ------------------------------

    @Test
    fun `a materialized plan line is a one-off item and NotesController tick accepts it`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)

        val item = planItems().single()
        assertNull(
            "repeatKind must stay null - this is the entire fix ticket 06 exists to make; a " +
                "non-null repeatKind here would mean NotesController.tick refuses this item exactly " +
                "the way it refused ticket 04's recurring version",
            item.repeatKind,
        )

        val ticked = NotesController.tick(context, item)
        assertTrue("a one-off item must be accepted by NotesController.tick's guard, not refused", ticked)

        val reread = CarDatabase.getDatabase(context).listItemDao().getById(item.id)
        assertNotNull(reread)
        assertTrue(reread!!.done)
        assertNotNull("a real done timestamp must exist for currentItems to read back", reread.doneAt)
    }

    @Test
    fun `currentItems reflects a real done state after ticking, not a skip proxy`() = runBlocking {
        val now = System.currentTimeMillis()
        givenAMealTarget(now)
        GoalChecklistSync.materializeToday(context, now)
        val item = planItems().single()
        NotesController.tick(context, item)

        val view = GoalChecklistSync.currentItems(context, now).single()
        assertTrue(view.done)
        assertNotNull(view.doneAt)
    }

    @Test
    fun `no plan yet reads as no items, never a zero-progress row`() = runBlocking {
        val now = System.currentTimeMillis()
        // No target of any kind written - materializeToday must not invent an item to represent
        // "nothing accepted", mirroring GoalChecklist.forToday's own hasPlan=false/items=empty rule.
        GoalChecklistSync.materializeToday(context, now)

        assertTrue(planItems().isEmpty())
        assertTrue(GoalChecklistSync.currentItems(context, now).isEmpty())
    }

    // --- retention: ticked and un-ticked plan items are removed TOGETHER outside the window -------

    @Test
    fun `plan items older than the retention window are trimmed, ticked and un-ticked alike`() = runBlocking {
        val now = System.currentTimeMillis()
        val list = NotesController.theList(context)
        val longAgo = now - (GoalChecklistSync.RETENTION_DAYS + 1) * 24 * 60 * 60 * 1000

        // Two plan items materialized "long ago" and never trimmed since - one ticked that day,
        // one left open, both directly inserted with a backdated createdAt the way an item that
        // genuinely aged past the window would look, since NotesController.addItem always stamps
        // "now".
        val db = CarDatabase.getDatabase(context)
        val doneOldId = db.listItemDao().insert(
            ListItem(
                listId = list.id, text = GoalChecklistSync.ITEM_PREFIX + "Sleep 8h",
                done = true, doneAt = longAgo, sortOrder = 0, createdAt = longAgo,
            ),
        )
        val openOldId = db.listItemDao().insert(
            ListItem(
                listId = list.id, text = GoalChecklistSync.ITEM_PREFIX + "Hit 2300 kcal / 180g protein",
                done = false, sortOrder = 1, createdAt = longAgo,
            ),
        )

        // A materialize call today is what triggers the trim (trim-on-write, matching
        // ConversationAuditDao.record's own convention) - a plan with no target today still runs
        // the trim half of materializeToday even though it derives zero wanted lines.
        GoalChecklistSync.materializeToday(context, now)

        assertNull("the ticked old item must be gone too - the denominator, not just the numerator, is removed", db.listItemDao().getById(doneOldId))
        assertNull("the un-ticked old item must be gone", db.listItemDao().getById(openOldId))
    }

    @Test
    fun `a plan item inside the retention window survives a materialize call`() = runBlocking {
        val now = System.currentTimeMillis()
        val list = NotesController.theList(context)
        val recent = now - (GoalChecklistSync.RETENTION_DAYS - 1) * 24 * 60 * 60 * 1000

        val db = CarDatabase.getDatabase(context)
        val recentId = db.listItemDao().insert(
            ListItem(
                listId = list.id, text = GoalChecklistSync.ITEM_PREFIX + "Sleep 8h",
                done = true, doneAt = recent, sortOrder = 0, createdAt = recent,
            ),
        )

        GoalChecklistSync.materializeToday(context, now)

        assertNotNull("an item still inside the window must not be swept up by the same trim pass", db.listItemDao().getById(recentId))
    }
}
