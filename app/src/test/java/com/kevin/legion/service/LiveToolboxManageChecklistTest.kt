package com.kevin.legion.service

import com.kevin.legion.checklists.ChecklistController
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
 * `manage_checklist` dispatch (one-today ticket 09's voice slice) - same Robolectric-plus-Room
 * shape [LiveToolboxGoalPlanTest]/[ChecklistControllerTest] already use. Every branch calls
 * straight into [ChecklistController], so this file is checking that the tool's action/result
 * plumbing matches that controller's own rules, never re-deciding what those rules are.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxManageChecklistTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dispatch(args: JSONObject) = runBlocking {
        LiveToolbox.dispatch(context, "manage_checklist", args)!!
    }

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in pairs) obj.put(k, v)
        return obj
    }

    // ------------------------------------------------------------------ create

    @Test
    fun `create makes a new checklist with no schedule by default`() = runBlocking {
        val result = dispatch(args("action" to "create", "list" to "bio"))
        assertTrue(result.getBoolean("success"))
        val lists = ChecklistController.allChecklists(context)
        assertEquals(1, lists.size)
        assertEquals("bio", lists.first().name)
        assertEquals(null, lists.first().scheduleKind)
    }

    @Test
    fun `create refuses a duplicate name in words and writes nothing extra`() = runBlocking {
        dispatch(args("action" to "create", "list" to "Bio"))
        val result = dispatch(args("action" to "create", "list" to "bio"))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("already"))
        assertEquals(1, ChecklistController.allChecklists(context).size)
    }

    // ------------------------------------------------------------------ tick on a measured item, no value

    @Test
    fun `tick on a measured item with no value returns the refusal and writes nothing`() = runBlocking {
        dispatch(args("action" to "create", "list" to "bio"))
        dispatch(
            args(
                "action" to "add", "list" to "bio", "text" to "walk",
                "unit" to "steps", "target" to 10000.0, "direction" to "at_least",
            ),
        )
        val result = dispatch(args("action" to "tick", "list" to "bio", "item" to "walk"))
        assertFalse(result.getBoolean("success"))
        assertEquals(
            "\"walk\" is measured in steps - give a number to tick it, nothing was recorded.",
            result.getString("message"),
        )

        val checklist = ChecklistController.allChecklists(context).first()
        val loaded = ChecklistController.itemsWithTickState(context, checklist.id)
                as ChecklistController.ChecklistItemsResult.Loaded
        assertFalse("no tick should have been written", loaded.items.first().ticked)
    }

    @Test
    fun `tick on a measured item with a value succeeds`() = runBlocking {
        dispatch(args("action" to "create", "list" to "bio"))
        dispatch(args("action" to "add", "list" to "bio", "text" to "walk", "unit" to "steps"))
        val result = dispatch(args("action" to "tick", "list" to "bio", "item" to "walk", "value" to 8400.0))
        assertTrue(result.getBoolean("success"))
    }

    // ------------------------------------------------------------------ unknown list

    @Test
    fun `an unknown list name is refused in words`() = runBlocking {
        val result = dispatch(args("action" to "read", "list" to "nonexistent"))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("No checklist named"))
    }

    // ------------------------------------------------------------------ ambiguous item match

    @Test
    fun `an ambiguous item match does nothing and names the candidates`() = runBlocking {
        dispatch(args("action" to "create", "list" to "bio"))
        dispatch(args("action" to "add", "list" to "bio", "text" to "morning squats"))
        dispatch(args("action" to "add", "list" to "bio", "text" to "morning lunges"))

        val result = dispatch(args("action" to "tick", "list" to "bio", "item" to "morning"))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("squats"))
        assertTrue(result.getString("message").contains("lunges"))

        val checklist = ChecklistController.allChecklists(context).first()
        val loaded = ChecklistController.itemsWithTickState(context, checklist.id)
                as ChecklistController.ChecklistItemsResult.Loaded
        assertTrue("no item should have been ticked by an ambiguous match", loaded.items.none { it.ticked })
    }

    // ------------------------------------------------------------------ lists reports schedules in words

    @Test
    fun `lists reports every checklist's schedule in words`() = runBlocking {
        dispatch(args("action" to "create", "list" to "bio", "schedule" to "daily"))
        dispatch(args("action" to "create", "list" to "maintenance"))

        val result = dispatch(args("action" to "lists"))
        assertTrue(result.getBoolean("success"))
        val arr = result.getJSONArray("lists")
        assertEquals(2, arr.length())
        val byName = (0 until arr.length()).associate {
            val row = arr.getJSONObject(it)
            row.getString("name") to row.getString("schedule")
        }
        assertEquals("Daily", byName["bio"])
        assertEquals("No schedule", byName["maintenance"])
    }

    @Test
    fun `a weekly create with no days is refused in words`() = runBlocking {
        val result = dispatch(args("action" to "create", "list" to "bio", "schedule" to "weekly"))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("days"))
    }
}
