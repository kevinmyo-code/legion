package com.kevin.legion.checklists

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.checklists.groceriesNameMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The built-in Groceries list on the phone (Kevin, 2026-10-05): found by its system key, never
 * created here, and never deleted, renamed or archived. The engine refuses the same writes; the
 * controller refuses first so the phone never applies locally what the household will not accept.
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistControllerBuiltInTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun syncedBuiltIn(): Long = CarDatabase.getDatabase(context).checklistDao().insert(
        Checklist(name = "Groceries", systemKey = Checklist.SYSTEM_KEY_GROCERIES),
    )

    @Test
    fun `no built-in list until the engine's copy has synced`() = runBlocking {
        ChecklistController.createChecklist(context, name = "Groceries") // a hand-made lookalike
        assertNull(ChecklistController.builtInGroceries(context))
    }

    @Test
    fun `the built-in list is found by system key`() = runBlocking {
        ChecklistController.createChecklist(context, name = "Groceries")
        val id = syncedBuiltIn()
        assertEquals(id, ChecklistController.builtInGroceries(context)?.id)
        assertTrue(ChecklistController.builtInGroceries(context)!!.isBuiltIn)
    }

    private suspend fun refused(block: suspend () -> Unit): String {
        try {
            block()
        } catch (e: ChecklistController.BuiltInListException) {
            return e.message.orEmpty()
        }
        fail("expected the built-in list to refuse")
        return ""
    }

    @Test
    fun `delete, rename and archive of the built-in list are refused in words and change nothing`() = runBlocking {
        val id = syncedBuiltIn()

        assertEquals(
            "The Groceries list is built in, so it can't be deleted",
            refused { ChecklistController.deleteChecklist(context, id) },
        )
        assertEquals(
            "The Groceries list is built in, so it can't be renamed",
            refused { ChecklistController.renameChecklist(context, id, "Shopping") },
        )
        assertEquals(
            "The Groceries list is built in, so it can't be archived",
            refused { ChecklistController.archiveChecklist(context, id) },
        )

        val after = ChecklistController.getChecklist(context, id)
        assertNotNull(after)
        assertEquals("Groceries", after!!.name)
        assertFalse(after.archived)
    }

    @Test
    fun `renaming it to its own name is a harmless no-op, and an ordinary list is unaffected`() = runBlocking {
        val id = syncedBuiltIn()
        ChecklistController.renameChecklist(context, id, "Groceries")

        val plain = ChecklistController.createChecklist(context, name = "Hardware")
        ChecklistController.renameChecklist(context, plain.id, "Tools")
        ChecklistController.archiveChecklist(context, plain.id)
        ChecklistController.deleteChecklist(context, plain.id)
        assertNull(ChecklistController.getChecklist(context, plain.id))
    }

    @Test
    fun `creating a list named Groceries says it exists, or that it is still on its way`() {
        assertEquals(
            "Groceries is the household's built-in list, so it already exists. Nothing was created.",
            groceriesNameMessage(builtInPresent = true),
        )
        assertEquals(
            "Getting the Groceries list from the household... Nothing was created.",
            groceriesNameMessage(builtInPresent = false),
        )
    }
}
