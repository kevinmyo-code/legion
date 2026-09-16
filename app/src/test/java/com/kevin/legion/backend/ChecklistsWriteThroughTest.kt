package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
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
 * [ChecklistsWriteThrough] through the door every real caller uses - [ChecklistController]'s own
 * mutators - because the two properties that matter here are properties of the PAIR: the local
 * write always stands, and only what could not be delivered is queued.
 *
 * The two branches this file exists to pin down, both of them CLAUDE.md section 7 in practice:
 *
 * - **Unreachable engine: the row is in Room, an outbox entry exists, and nothing claims delivery.**
 *   The calendar day view reads exactly that outbox entry
 *   ([ChecklistsOutboxDrain.queuedItemIdsForDay]) to label the row "Queued - not on the engine yet."
 * - **A 400 refusal is NOT queued.** The engine saw the request and said no in words; retrying it
 *   every foreground forever would be a retry loop around a rejection, so the refusal's own
 *   sentence comes back instead and the queue stays empty.
 *
 * **`ChecklistController.tick`/`untick`'s own push is LAUNCHED, not awaited** (the fix for the
 * ~1s checkbox-tap latency Kevin reported on the phone) - see `ChecklistController.pushScope`'s own
 * doc comment. `tick()`'s caller can no longer deterministically observe when that push finishes -
 * that IS the fix, so **no test in this file may assert on the push's outcome in the same breath it
 * calls [ChecklistController.tick]/`untick`.** Two shapes that tried to anyway, both worth naming so
 * a later change does not repeat them: a bare `CopyOnWriteArrayList<Job>` "await what I launched"
 * seam does not fix the underlying race (a test that forgets to await, or Robolectric's own
 * teardown, is still exposed to a leaked write landing on an unrelated later test - this shipped for
 * real, `testDebugUnitTest` went from 3548/3548 green to `VoiceNoteControllerTest` flaking on an
 * unrelated method); pointing [ChecklistController.pushScopeOverride] at a `runTest`'s own
 * `TestScope` and calling `advanceUntilIdle()` mid-block does not fix it EITHER, because Room's
 * generated suspend DAOs hop onto Room's own real query executor - a real dispatcher
 * `advanceUntilIdle()` cannot drain, so a mid-block assertion raced it deterministically LOSING both
 * times it was tried.
 *
 * **What actually works, below:** a test that needs to see [ChecklistController.tick]'s OWN local
 * write (`stays local`) still calls it, still wrapped in [runTickTest] so its launched push is a
 * genuine child of the test's [TestScope] and is therefore fully joined - never leaked past this
 * test - by the time the test method returns, even though nothing here reads its result. A test
 * that needs to see the QUEUING behaviour (`is queued`) never calls [ChecklistController.tick] at
 * all: it writes a [ChecklistTick] row directly, the same way `ChecklistControllerTest`'s own
 * `backdatedChecklist` helper writes a [com.kevin.legion.data.local.Checklist] row directly, and
 * then calls [ChecklistsWriteThrough] itself - a plain suspend call, nothing launched, nothing to
 * race. Nothing else in this file changed: `renameChecklist`/`createChecklist` still push
 * synchronously through `checklistChanged`, which this ticket deliberately leaves alone.
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistsWriteThroughTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Every call fails the way [failure] says. Records what was attempted so a test can prove a
     * refused write was tried exactly once and never re-queued. */
    private class FailingBackend(private val failure: Throwable) : ChecklistsBackend {
        val calls = mutableListOf<String>()

        private fun <T> fail(name: String): Result<T> {
            calls += name
            return Result.failure(failure)
        }

        override suspend fun fetchChanges(sinceIso: String?) = fail<ChecklistChanges>("fetchChanges")
        override suspend fun upsertChecklist(syncId: String, fields: ChecklistFields) =
            fail<RemoteChecklist>("upsertChecklist")
        override suspend fun patchChecklist(serverId: String, syncId: String, fields: ChecklistFields) =
            fail<RemoteChecklist>("patchChecklist")
        override suspend fun deleteChecklist(serverId: String) = fail<Boolean>("deleteChecklist")
        override suspend fun upsertItem(checklistServerId: String, syncId: String, fields: ChecklistItemFields) =
            fail<RemoteChecklistItem>("upsertItem")
        override suspend fun patchItem(
            checklistServerId: String,
            itemServerId: String,
            syncId: String,
            fields: ChecklistItemFields,
        ) = fail<RemoteChecklistItem>("patchItem")
        override suspend fun deleteItem(checklistServerId: String, itemServerId: String) = fail<Boolean>("deleteItem")
        override suspend fun tick(
            checklistServerId: String,
            itemServerId: String,
            day: Int,
            value: Double?,
            source: String,
        ) = fail<RemoteChecklistTick>("tick")
        override suspend fun untick(checklistServerId: String, itemServerId: String, day: Int) =
            fail<Boolean>("untick")
    }

    private fun unreachable() = FailingBackend(
        EngineHttpException(
            EngineFailure.Unreachable("http://192.168.1.117:8000", "Could not reach the engine, nothing was sent."),
        ),
    )

    private fun refusing(sentence: String) =
        FailingBackend(EngineHttpException(EngineFailure.Refused(400, sentence)))

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun tearDown() {
        ChecklistController.backendOverride = null
        ChecklistController.pushScopeOverride = null
    }

    /** Runs [block] with [ChecklistController.pushScopeOverride] pointed at THIS call's own
     * [TestScope] - so anything [ChecklistController.tick]/`untick` launches is a genuine CHILD of
     * this test's own coroutine hierarchy and is fully joined by the time [runTest] returns, rather
     * than leaking onto a real thread pool that could still be running when a LATER test's own
     * Robolectric environment has already been torn down. See this class's own doc comment for the
     * two shapes that tried to make the push's result OBSERVABLE mid-block instead, and failed. */
    private fun runTickTest(block: suspend TestScope.() -> Unit) = runTest {
        ChecklistController.pushScopeOverride = this
        block()
    }

    @Test
    fun `a tick made while the engine is unreachable stays local`() = runTickTest {
        ChecklistController.backendOverride = unreachable()
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "3 sets goblet squats")

        val outcome = ChecklistController.tick(context, item.id, day = 20_703)

        // The tick itself is real and local - the caller is told it was ticked, because it was.
        // The push this also kicks off is launched, not awaited (see [ChecklistController.tick]'s
        // own doc comment), so its outcome is deliberately NOT asserted here - see
        // `a tick made while the engine is unreachable is queued`, below, for that.
        assertTrue(outcome is ChecklistController.TickOutcome.Ticked)
        val db = CarDatabase.getDatabase(context)
        val stored = db.checklistTickDao().getForItemOnDay(item.id, 20_703)
        assertNotNull(stored)
        assertNull(stored!!.serverId) // never round-tripped, so it never earned a server id
    }

    @Test
    fun `a tick made while the engine is unreachable is queued`() = runBlocking {
        // Never calls ChecklistController.tick - writes the ChecklistTick row directly, the same
        // way ChecklistControllerTest's own backdatedChecklist helper writes a Checklist row
        // directly, so this test has nothing launched and nothing to race (see this class's own
        // doc comment for why).
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "3 sets goblet squats")
        val tickDao = CarDatabase.getDatabase(context).checklistTickDao()
        tickDao.insert(ChecklistTick(itemId = item.id, day = 20_703))

        val backend = unreachable()
        ChecklistsWriteThrough(context, backend).ticked(item.id, 20_703)

        // The row is queued, which is what the day view labels in words.
        val queued = ChecklistsOutboxDrain.queuedItemIdsForDay(context, 20_703)
        assertTrue(item.id in queued)
        // A tick queued for a DIFFERENT day is not this day's business.
        assertTrue(ChecklistsOutboxDrain.queuedItemIdsForDay(context, 20_704).isEmpty())
    }

    @Test
    fun `a refused write is reported in the engine's own words and is never queued`() = runBlocking {
        val sentence =
            "\"walk 10k steps\" is measured in steps - give a number to tick it, nothing was recorded."
        val backend = refusing(sentence)
        ChecklistController.backendOverride = backend
        val checklist = ChecklistController.createChecklist(context, "fitness")

        val outcome = ChecklistsWriteThrough(context, backend).checklistChanged(checklist.id)

        assertTrue(outcome is ChecklistsWriteThrough.PushOutcome.Refused)
        assertEquals(sentence, (outcome as ChecklistsWriteThrough.PushOutcome.Refused).message)
        // Nothing queued: a 400 means the engine understood the request and refused it, and a
        // retry loop around a rejection is worse than surfacing it.
        val queued = CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.CHECKLISTS, ChecklistsOutboxDrain.MAX_ATTEMPTS)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `three offline renames of the same row queue ONE entry, not three`() = runBlocking {
        ChecklistController.backendOverride = unreachable()
        val checklist = ChecklistController.createChecklist(context, "bio")

        ChecklistController.renameChecklist(context, checklist.id, "bio v2")
        ChecklistController.renameChecklist(context, checklist.id, "bio v3")
        ChecklistController.renameChecklist(context, checklist.id, "bio v4")

        // The drain re-reads the row's CURRENT state, so a second identical entry would only mean
        // a second identical push - see ChecklistsOutboxDrain's own class doc.
        val queued = CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.CHECKLISTS, ChecklistsOutboxDrain.MAX_ATTEMPTS)
        assertEquals(1, queued.size)
        // The local write stood every time regardless.
        assertEquals("bio v4", CarDatabase.getDatabase(context).checklistDao().getById(checklist.id)!!.name)
    }

    @Test
    fun `on an install not on the engine transport nothing is pushed and nothing is queued`() = runBlocking {
        // backendOverride left null and EngineBackends answers null too (no engine address, and
        // the checklists transport row untouched) - the default state of every install. With no
        // backend, ChecklistsWriteThrough.run() returns PushOutcome.NotConfigured before touching
        // Room at all, so the push [ChecklistController.tick] launches here does nothing there is
        // any race to have - no [runTickTest] needed.
        ChecklistController.backendOverride = null
        val checklist = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, checklist.id, "squats")
        ChecklistController.tick(context, item.id, day = 20_703)

        val dao = CarDatabase.getDatabase(context).outboxDao()
        assertTrue(dao.pendingForTable(OutboxTarget.CHECKLISTS, ChecklistsOutboxDrain.MAX_ATTEMPTS).isEmpty())
        assertTrue(dao.pendingForTable(OutboxTarget.CHECKLIST_ITEMS, ChecklistsOutboxDrain.MAX_ATTEMPTS).isEmpty())
        assertTrue(dao.pendingForTable(OutboxTarget.CHECKLIST_TICKS, ChecklistsOutboxDrain.MAX_ATTEMPTS).isEmpty())
        // The local rows are all there, exactly as before this ticket existed.
        assertNotNull(CarDatabase.getDatabase(context).checklistTickDao().getForItemOnDay(item.id, 20_703))
    }
}
