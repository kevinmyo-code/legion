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
 * A checklist add whose whole item is a bare pronoun names nothing (phone audit 2026-11-19: a line
 * literally named "it" was added). A closed list of words, matched on the whole item only: nothing is
 * added and the result asks what to add (`needs_answer`). Made-up lists and items.
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistVagueAddTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        dispatch(JSONObject().put("action", "create").put("list", "Errands"))
        Unit
    }

    @After
    fun tearDown() = RoomTestReset.drainArchDiskIoPool()

    private fun dispatch(args: JSONObject) = runBlocking { LiveToolbox.dispatch(context, "manage_checklist", args)!! }

    private fun add(item: String) = dispatch(JSONObject().put("action", "add").put("list", "Errands").put("text", item))

    private fun lines() = runBlocking {
        val list = ChecklistController.allChecklists(context).first { it.name == "Errands" }
        ChecklistController.itemsFor(context, list.id).map { it.text }
    }

    @Test
    fun `a bare pronoun is refused, asks what to add, and adds nothing`() {
        for (word in listOf("it", "It.", "that", "this", "them", "those", "these", "something", " this one ")) {
            val r = add(word)
            assertFalse("$word: $r", r.getBoolean("success"))
            assertTrue("$word: $r", r.optBoolean("needs_answer"))
            assertTrue(r.getString("message"), r.getString("message").contains("What should I add"))
        }
        assertEquals(emptyList<String>(), lines())
    }

    @Test
    fun `an item that only contains such a word is added`() {
        assertTrue(add("stamps for it").getBoolean("success"))
        assertTrue(add("kitchen sponge").getBoolean("success"))
        assertEquals(listOf("stamps for it", "kitchen sponge"), lines())
    }
}
