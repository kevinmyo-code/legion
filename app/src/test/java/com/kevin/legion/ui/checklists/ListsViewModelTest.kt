package com.kevin.legion.ui.checklists

import com.kevin.legion.backend.ChecklistChanges
import com.kevin.legion.backend.ChecklistFields
import com.kevin.legion.backend.ChecklistItemFields
import com.kevin.legion.backend.ChecklistsBackend
import com.kevin.legion.backend.ChecklistsOutboxDrain
import com.kevin.legion.backend.ChecklistsWriteThrough
import com.kevin.legion.backend.RemoteChecklist
import com.kevin.legion.backend.RemoteChecklistItem
import com.kevin.legion.backend.RemoteChecklistTick
import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.checklists.ChecklistController.ChecklistItemsResult
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Audit finding 3 (home-launcher ticket 04's own untick-trap fix, re-checked): the pre-fix lookup
 * asked [ChecklistsOutboxDrain.queuedItemIdsForDay] about [ChecklistController.today] alone, so a
 * PLAIN-list item ticked on an EARLIER day - and still queued because that tick failed to reach
 * the engine - lost its "Not synced yet" badge the moment "today" moved on from the day the tick
 * actually landed on. [queuedIdsForRows] is the fix: one lookup per distinct
 * [ChecklistController.ItemState.tickDay] among a list's own rows, plus today.
 *
 * Reproduces the bug end to end through the same doors [ListsViewModel.loadDetail] uses
 * ([ChecklistController.itemsWithTickState], [queuedIdsForRows]) rather than driving
 * [ListsViewModel] itself through `viewModelScope` - no `Dispatchers.Main`/Robolectric-looper
 * plumbing needed for a plain suspend call, the same posture
 * `ChecklistsWriteThroughTest`'s own class doc comment already settled on for this file's queuing
 * assertions.
 */
@RunWith(RobolectricTestRunner::class)
class ListsViewModelTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** A minimal always-fails [ChecklistsBackend] - only [ChecklistsWriteThrough.ticked] (via the
     * `tick` method) is ever exercised below; the rest exist only to satisfy the interface. */
    private fun unreachableBackend() = object : ChecklistsBackend {
        private fun <T> fail(): Result<T> = Result.failure(
            EngineHttpException(
                EngineFailure.Unreachable("http://192.168.1.117:8000", "Could not reach the engine, nothing was sent."),
            ),
        )
        override suspend fun fetchChanges(sinceIso: String?) = fail<ChecklistChanges>()
        override suspend fun upsertChecklist(syncId: String, fields: ChecklistFields) = fail<RemoteChecklist>()
        override suspend fun patchChecklist(serverId: String, syncId: String, fields: ChecklistFields) =
            fail<RemoteChecklist>()
        override suspend fun deleteChecklist(serverId: String) = fail<Boolean>()
        override suspend fun upsertItem(checklistServerId: String, syncId: String, fields: ChecklistItemFields) =
            fail<RemoteChecklistItem>()
        override suspend fun patchItem(
            checklistServerId: String,
            itemServerId: String,
            syncId: String,
            fields: ChecklistItemFields,
        ) = fail<RemoteChecklistItem>()
        override suspend fun deleteItem(checklistServerId: String, itemServerId: String) = fail<Boolean>()
        override suspend fun tick(
            checklistServerId: String,
            itemServerId: String,
            day: Int,
            value: Double?,
            source: String,
        ) = fail<RemoteChecklistTick>()
        override suspend fun untick(checklistServerId: String, itemServerId: String, day: Int) = fail<Boolean>()
    }

    @Test
    fun `a plain-list tick queued on an earlier day still shows in the queued set, not just today`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "milk")
        val today = ChecklistController.today()
        val earlierDay = today - 3

        // The tick itself lives on an earlier day - direct Room write, same pattern
        // ChecklistControllerTest's own backdatedChecklist helper uses for a Checklist row.
        CarDatabase.getDatabase(context).checklistTickDao().insert(ChecklistTick(itemId = item.id, day = earlierDay))
        // That tick never reached the engine and is queued FOR earlierDay, never for today.
        ChecklistsWriteThrough(context, unreachableBackend()).ticked(item.id, earlierDay)

        val result = ChecklistController.itemsWithTickState(context, checklist.id, today) as ChecklistItemsResult.Loaded
        val row = result.items.single { it.item.id == item.id }
        assertEquals(earlierDay, row.tickDay)

        // The bug: a lookup scoped to ONLY today misses this entirely.
        assertTrue(ChecklistsOutboxDrain.queuedItemIdsForDay(context, today).isEmpty())

        // The fix: queuedIdsForRows folds in every row's own tickDay too.
        val queued = queuedIdsForRows(context, result.items.map { it.tickDay }, today)
        assertTrue(item.id in queued)
    }

    @Test
    fun `a tick queued for today alone is still found - the common case is unaffected`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "eggs")
        val today = ChecklistController.today()

        CarDatabase.getDatabase(context).checklistTickDao().insert(ChecklistTick(itemId = item.id, day = today))
        ChecklistsWriteThrough(context, unreachableBackend()).ticked(item.id, today)

        val result = ChecklistController.itemsWithTickState(context, checklist.id, today) as ChecklistItemsResult.Loaded
        val queued = queuedIdsForRows(context, result.items.map { it.tickDay }, today)
        assertTrue(item.id in queued)
    }

    @Test
    fun `an item with nothing queued at all is not in the set`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "bread")
        val today = ChecklistController.today()

        val queued = queuedIdsForRows(context, listOf(null), today)
        assertTrue(item.id !in queued)
    }

    /**
     * Audit finding 6: every [ListsViewModel] write - tick/untick/addItem/rename/archiveToggle/
     * confirmDelete/reorder/saveEdit/setSchedule/deleteItem via [ListsViewModel.guardedWrite],
     * createList via its own catch - builds its on-screen sentence through [writeErrorMessage],
     * never a bare `e.message` or a silent swallow. Pinned here as a plain function so the wording
     * is tested without driving [ListsViewModel]'s `viewModelScope.launch` through a coroutine test
     * harness this codebase does not yet have (see [ListsViewModel.guardedWrite]'s own doc comment
     * for why [writeErrorMessage] was pulled out rather than asserted only through the ViewModel).
     */
    @Test
    fun `a thrown write becomes a Couldn't sentence naming the action and the reason`() {
        val message = writeErrorMessage("delete that item", RuntimeException("disk full"))
        assertEquals("Couldn't delete that item - disk full.", message)
    }

    @Test
    fun `a thrown write with no message still states the action, not a blank reason`() {
        val message = writeErrorMessage("archive that list", RuntimeException())
        assertEquals("Couldn't archive that list - unknown error.", message)
    }
}
