package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.testutil.RoomTestReset
import java.time.LocalDate
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Purchase-log ticket 08: every untick sent to the engine carries the caller's own local epoch day
 * as `today`, so a same-day untick on the Groceries list removes the bought entry the tick made
 * (ADR 0055) and a later one does not. The engine falls back to the UTC date without it, which
 * west of UTC keeps an entry a late-evening untick meant to remove.
 *
 * Driven through [ChecklistsWriteThrough] and [ChecklistsOutboxDrain] directly (never
 * `ChecklistController.untick`, whose push is launched and not awaitable - see
 * `ChecklistsWriteThroughTest`'s own class doc).
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistsUntickTodayTest {

    private val context = RuntimeEnvironment.getApplication()

    private class RecordingBackend(private val untickResult: Result<Boolean>) : ChecklistsBackend {
        /** (day, today) of every four-argument untick; a three-argument one is a failure of this feature. */
        val unticks = mutableListOf<Pair<Int, Int>>()
        var threeArgCalls = 0

        private fun <T> notUsed(): Result<T> = error("not used by this test")
        override suspend fun fetchChanges(sinceIso: String?) = notUsed<ChecklistChanges>()
        override suspend fun upsertChecklist(syncId: String, fields: ChecklistFields) = notUsed<RemoteChecklist>()
        override suspend fun patchChecklist(serverId: String, syncId: String, fields: ChecklistFields) =
            notUsed<RemoteChecklist>()
        override suspend fun deleteChecklist(serverId: String) = notUsed<Boolean>()
        override suspend fun upsertItem(checklistServerId: String, syncId: String, fields: ChecklistItemFields) =
            notUsed<RemoteChecklistItem>()
        override suspend fun patchItem(
            checklistServerId: String,
            itemServerId: String,
            syncId: String,
            fields: ChecklistItemFields,
        ) = notUsed<RemoteChecklistItem>()
        override suspend fun deleteItem(checklistServerId: String, itemServerId: String) = notUsed<Boolean>()
        override suspend fun tick(
            checklistServerId: String,
            itemServerId: String,
            day: Int,
            value: Double?,
            source: String,
        ) = notUsed<RemoteChecklistTick>()
        override suspend fun untick(checklistServerId: String, itemServerId: String, day: Int): Result<Boolean> {
            threeArgCalls++
            return untickResult
        }
        override suspend fun untick(
            checklistServerId: String,
            itemServerId: String,
            day: Int,
            today: Int,
        ): Result<Boolean> {
            unticks += day to today
            return untickResult
        }
    }

    private val unreachable: Result<Boolean> = Result.failure(
        EngineHttpException(EngineFailure.Unreachable("http://192.168.1.117:8000", "Could not reach the engine.")),
    )

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun tearDown() {
        ChecklistController.pushScopeOverride = null
        // Drain, then CLOSE the database inside this method's own lifecycle: the outbox writes this
        // class makes schedule Room invalidation refreshes, and one that outlives the method runs
        // against Robolectric's torn-down SQLite and is blamed on whatever runTest comes next (it
        // showed up as AppFoldersScreenshotTest failing in the full suite, 3 runs in 4, and
        // disappeared when this class was ignored). A plain drain was not enough here.
        RoomTestReset.resetCarDatabaseSingleton()
        RoomTestReset.drainArchDiskIoPool()
    }

    /** `createChecklist`/`addItem` launch their own push; pointing that scope at this test's own
     * [TestScope] makes it a child that is joined before the test returns, instead of a stray write
     * onto a real thread that can outlive the Robolectric environment and be blamed on whatever test
     * runs next (see `ChecklistsWriteThroughTest`'s class doc). */
    private fun runTickTest(block: suspend TestScope.() -> Unit) = runTest {
        ChecklistController.pushScopeOverride = this
        block()
    }

    /** A Groceries item that already has server ids, so no parent push is needed to address it. */
    private suspend fun syncedItem(): Long {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "shampoo")
        val db = CarDatabase.getDatabase(context)
        db.checklistSyncDao().setServerId(checklist.id, "c-srv")
        db.checklistItemSyncDao().setServerId(item.id, "i-srv")
        db.checklistTickDao().insert(ChecklistTick(itemId = item.id, day = 20_000))
        return item.id
    }

    @Test
    fun `an untick sends the local day it was made on, not the day the tick lives on`() = runTickTest {
        val itemId = syncedItem()
        val backend = RecordingBackend(Result.success(true))
        val before = LocalDate.now().toEpochDay().toInt()

        ChecklistsWriteThrough(context, backend).unticked(itemId, 20_000)

        val after = LocalDate.now().toEpochDay().toInt()
        val (day, today) = backend.unticks.single()
        assertEquals("the tick's own day", 20_000, day)
        assertTrue("today is the local epoch day, not the tick's day", today in before..after)
        assertEquals("never the old three-argument call", 0, backend.threeArgCalls)
    }

    @Test
    fun `a queued untick keeps the day it was made and sends that, not the day it drains`() = runTickTest {
        val itemId = syncedItem()
        val before = LocalDate.now().toEpochDay().toInt()
        ChecklistsWriteThrough(context, RecordingBackend(unreachable)).unticked(itemId, 20_000)

        val queued = CarDatabase.getDatabase(context).outboxDao()
            .pendingForTable(OutboxTarget.CHECKLIST_TICKS, ChecklistsOutboxDrain.MAX_ATTEMPTS).single()
        val payload = Json.decodeFromString(ChecklistTickOutboxPayload.serializer(), queued.payload)
        val madeOn = payload.today!!
        val after = LocalDate.now().toEpochDay().toInt()
        assertTrue("the payload carries the day it was unticked", madeOn in before..after)

        // Rewrite the payload as if it had been queued two days ago, then drain.
        val twoDaysAgo = before - 2
        val outbox = CarDatabase.getDatabase(context).outboxDao()
        outbox.delete(queued.id)
        outbox.insert(
            queued.copy(
                id = 0,
                payload = Json.encodeToString(
                    ChecklistTickOutboxPayload.serializer(),
                    payload.copy(today = twoDaysAgo),
                ),
            ),
        )
        val drainBackend = RecordingBackend(Result.success(true))
        ChecklistsOutboxDrain.drain(context, drainBackend)

        assertEquals(20_000 to twoDaysAgo, drainBackend.unticks.single())
    }
}
