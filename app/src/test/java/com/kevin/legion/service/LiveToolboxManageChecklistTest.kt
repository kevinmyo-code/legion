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

    // ------------------------------------------------------------------ add: text, and the item alias

    @Test
    fun `add accepts item as an alias for text`() = runBlocking {
        // Observed on the phone 2026-09-06: the model sent `item` (right for tick/untick/remove) on
        // an `add`, was refused, called `lists` to re-orient and retried with `text` - three calls
        // for one unambiguous request. The alias is what removes the round trip; the tool
        // description says so in one clause rather than leaving the model to find it by failing.
        dispatch(args("action" to "create", "list" to "Errands"))

        val result = dispatch(args("action" to "add", "list" to "Errands", "item" to "oat milk"))

        assertTrue(result.getBoolean("success"))
        assertEquals("Added \"oat milk\" to \"Errands\".", result.getString("message"))
        val checklist = ChecklistController.allChecklists(context).first()
        assertEquals(listOf("oat milk"), ChecklistController.itemsFor(context, checklist.id).map { it.text })
    }

    @Test
    fun `text still wins when both are sent`() = runBlocking {
        dispatch(args("action" to "create", "list" to "Errands"))

        val result = dispatch(
            args("action" to "add", "list" to "Errands", "text" to "oat milk", "item" to "whole milk"),
        )

        assertTrue(result.getBoolean("success"))
        val checklist = ChecklistController.allChecklists(context).first()
        assertEquals(listOf("oat milk"), ChecklistController.itemsFor(context, checklist.id).map { it.text })
    }

    @Test
    fun `add with neither text nor item names the parameter, and adds nothing`() = runBlocking {
        dispatch(args("action" to "create", "list" to "Errands"))

        val result = dispatch(args("action" to "add", "list" to "Errands"))

        assertFalse(result.getBoolean("success"))
        assertEquals(
            "add needs the line in \"text\"; nothing was added to \"Errands\".",
            result.getString("message"),
        )
        val checklist = ChecklistController.allChecklists(context).first()
        assertTrue(ChecklistController.itemsFor(context, checklist.id).isEmpty())
    }

    @Test
    fun `the refusal says you sent item only when item really was sent`() = runBlocking {
        dispatch(args("action" to "create", "list" to "Errands"))

        val result = dispatch(args("action" to "add", "list" to "Errands", "item" to "  "))

        assertFalse(result.getBoolean("success"))
        assertEquals(
            "add needs the line in \"text\" (you sent \"item\", and it was empty); " +
                "nothing was added to \"Errands\".",
            result.getString("message"),
        )
        val checklist = ChecklistController.allChecklists(context).first()
        assertTrue(ChecklistController.itemsFor(context, checklist.id).isEmpty())
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

    // ------------------------------------------------------------------ the built-in Groceries list

    private suspend fun syncedBuiltInGroceries(): Long =
        com.kevin.legion.data.local.CarDatabase.getDatabase(context).checklistDao().insert(
            com.kevin.legion.data.local.Checklist(
                name = "Groceries",
                systemKey = com.kevin.legion.data.local.Checklist.SYSTEM_KEY_GROCERIES,
            ),
        )

    @Test
    fun `Groceries resolves to the built-in list even beside a hand-made one of the same name`() = runBlocking {
        ChecklistController.createChecklist(context, name = "Groceries")
        val builtIn = syncedBuiltInGroceries()

        val result = dispatch(args("action" to "add", "list" to "groceries", "text" to "oat milk"))

        assertTrue(result.getString("message"), result.getBoolean("success"))
        assertEquals(listOf("oat milk"), ChecklistController.itemsFor(context, builtIn).map { it.text })
    }

    @Test
    fun `with no built-in list yet, add says it is on its way and creates nothing`() = runBlocking {
        val result = dispatch(args("action" to "add", "list" to "Groceries", "text" to "oat milk"))

        assertFalse(result.getBoolean("success"))
        val message = result.getString("message")
        assertTrue(message, message.startsWith("Getting the Groceries list from the household..."))
        assertTrue(ChecklistController.allChecklists(context, includeArchived = true).isEmpty())
    }

    @Test
    fun `create of Groceries never creates one, whether the built-in is here or not`() = runBlocking {
        val missing = dispatch(args("action" to "create", "list" to "Groceries"))
        assertFalse(missing.getBoolean("success"))
        assertTrue(missing.getString("message").startsWith("Getting the Groceries list from the household..."))

        syncedBuiltInGroceries()
        val present = dispatch(args("action" to "create", "list" to "Groceries"))
        assertFalse(present.getBoolean("success"))
        assertTrue(present.getString("message").contains("built-in"))
        assertEquals(1, ChecklistController.allChecklists(context, includeArchived = true).size)
    }

    // ------------------------------------------------------------------ ticked / clear_ticked

    // 2026-11-10: asked to delete every ticked line, the assistant could only remove them one
    // name at a time and could not even list which were ticked.
    @Test
    fun `ticked lists and counts the ticked lines and clear_ticked removes them in one call`() = runBlocking {
        dispatch(args("action" to "create", "list" to "Camping"))
        for (line in listOf("tent pegs", "lantern", "marshmallows")) {
            dispatch(args("action" to "add", "list" to "Camping", "text" to line))
        }
        dispatch(args("action" to "tick", "list" to "Camping", "item" to "tent pegs"))
        dispatch(args("action" to "tick", "list" to "Camping", "item" to "lantern"))

        val ticked = dispatch(args("action" to "ticked", "list" to "Camping"))
        assertTrue(ticked.getBoolean("success"))
        assertEquals(2, ticked.getInt("ticked_count"))
        assertEquals(1, ticked.getInt("open_count"))
        val names = (0 until ticked.getJSONArray("ticked").length()).map { ticked.getJSONArray("ticked").getString(it) }
        assertEquals(listOf("lantern", "tent pegs"), names.sorted())

        val cleared = dispatch(args("action" to "clear_ticked", "list" to "Camping"))
        assertTrue(cleared.getBoolean("success"))
        assertEquals(2, cleared.getInt("removed_count"))
        val checklist = ChecklistController.allChecklists(context).first()
        assertEquals(listOf("marshmallows"), ChecklistController.itemsFor(context, checklist.id).map { it.text })

        val again = dispatch(args("action" to "clear_ticked", "list" to "Camping"))
        assertTrue(again.getString("message"), again.getString("message").contains("nothing was removed"))
    }

    @Test
    fun `clear_ticked on a routine is refused in words`() = runBlocking {
        dispatch(args("action" to "create", "list" to "Mornings", "schedule" to "daily"))
        dispatch(args("action" to "add", "list" to "Mornings", "text" to "water the plants"))
        dispatch(args("action" to "tick", "list" to "Mornings", "item" to "water the plants"))
        val result = dispatch(args("action" to "clear_ticked", "list" to "Mornings"))
        assertFalse(result.getBoolean("success"))
        assertTrue(result.getString("message").contains("nothing was removed"))
    }
}
