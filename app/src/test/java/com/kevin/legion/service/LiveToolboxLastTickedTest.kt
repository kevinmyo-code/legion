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
 * `get_last_ticked` (web-calendar-and-lists ticket 04) - both its dispatch plumbing (same
 * Robolectric-plus-Room shape [LiveToolboxManageChecklistTest] uses) and its own §4-rule-5
 * exposure: a tick is not a purchase, and this is the only automated grip ticket 04 names on that
 * rule (its description can never say "bought"; a wrong claim here would ship on every call, not
 * just the one the phone verification step happens to try).
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxLastTickedTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun dispatch(item: String) = runBlocking {
        LiveToolbox.dispatch(context, "get_last_ticked", JSONObject().put("item", item))!!
    }

    // ------------------------------------------------------------------ the description itself

    @Test
    fun `the tool description never claims a purchase, and says ticked instead`() {
        val live = LiveToolbox.declarations()
        val decl = (0 until live.length()).map { live.getJSONObject(it) }
            .first { it.getString("name") == "get_last_ticked" }
        val description = decl.getString("description")

        assertFalse(
            "get_last_ticked's description must never claim a tick is a purchase - CLAUDE.md §4 " +
                "rule 5, ticket 04's own wording table",
            description.contains("you bought", ignoreCase = true),
        )
        assertTrue(
            "get_last_ticked's description must say the word \"ticked\" - what a tick actually is",
            description.contains("ticked", ignoreCase = true),
        )
    }

    // ------------------------------------------------------------------ found, spoken correctly

    @Test
    fun `a real tick is reported as ticked, never as bought`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "toothpaste")
        ChecklistController.tick(context, item.id, day = ChecklistController.today())

        val result = dispatch("toothpaste")

        assertTrue(result.getBoolean("success"))
        assertEquals("Groceries", result.getString("lastTickedChecklist"))
        val message = result.getString("message")
        assertFalse("the spoken message must never say \"bought\"", message.contains("bought", ignoreCase = true))
        assertTrue("the spoken message must say \"ticked\"", message.contains("ticked", ignoreCase = true))
    }

    // ------------------------------------------------------------------ absence is a record gap, not "never bought"

    @Test
    fun `no record is reported as no record, never as never bought`() = runBlocking {
        val result = dispatch("toothpaste")

        assertTrue("an absent tick is still a successful, honest answer", result.getBoolean("success"))
        val message = result.getString("message")
        assertFalse("must never assert a purchase never happened", message.contains("never bought", ignoreCase = true))
        assertFalse("must never assert a purchase never happened", message.contains("never buy", ignoreCase = true))
        assertTrue("must say plainly that there's no record", message.contains("no record", ignoreCase = true))
    }

    // ------------------------------------------------------------------ blank item

    @Test
    fun `a blank item is refused in words rather than searched`() = runBlocking {
        val result = dispatch("  ")
        assertFalse(result.getBoolean("success"))
    }

    // ------------------------------------------------------------------ 03's narrowness, reachable through the tool

    @Test
    fun `Colgate toothpaste does not answer for a plain toothpaste query`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "Colgate toothpaste")
        ChecklistController.tick(context, item.id, day = ChecklistController.today())

        val result = dispatch("toothpaste")

        assertTrue(result.getBoolean("success"))
        assertTrue(
            "no match should read the same as a genuinely empty history",
            result.getString("message").contains("no record", ignoreCase = true),
        )
    }
}
