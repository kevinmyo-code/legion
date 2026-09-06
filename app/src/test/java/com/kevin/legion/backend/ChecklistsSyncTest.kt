package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ChecklistsSync.pull] - the five merge rules, reproduced from [EventsSync.pull] as this ticket's
 * brief requires and asserted here rather than assumed. `EventsSyncTest` is the original; this
 * file walks the same cases over the three checklist tables, plus the two things that are genuinely
 * different here: a tick matched on `(item, day)` rather than on a sync id, and a `createdAt` the
 * engine is not allowed to overwrite.
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistsSyncTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Hands back exactly what it is constructed with, and records the watermark it was asked
     * for - which is how the "missing watermark fetches everything" rule gets checked. */
    private class StaticBackend(private val changes: ChecklistChanges) : ChecklistsBackend {
        var lastSince: String? = "never called"

        override suspend fun fetchChanges(sinceIso: String?): Result<ChecklistChanges> {
            lastSince = sinceIso
            return Result.success(changes)
        }

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
        override suspend fun untick(checklistServerId: String, itemServerId: String, day: Int) = notUsed<Boolean>()

        private fun <T> notUsed(): Result<T> =
            Result.failure(ChecklistsBackendException("not used by ChecklistsSyncTest"))
    }

    private fun changes(
        checklists: List<RemoteChecklist> = emptyList(),
        items: List<RemoteChecklistItem> = emptyList(),
        ticks: List<RemoteChecklistTick> = emptyList(),
        serverTime: String = "2026-09-06T12:00:00Z",
    ) = ChecklistChanges(serverTime, checklists, items, ticks)

    private fun remoteChecklist(
        serverId: String,
        syncId: String?,
        name: String,
        updatedAtMs: Long,
        deleted: Boolean = false,
    ) = RemoteChecklist(
        serverId = serverId,
        syncId = syncId,
        name = name,
        scheduleKind = null,
        scheduleEvery = null,
        scheduleDaysOfWeek = null,
        sortOrder = 0,
        archived = false,
        createdAtMs = 9_000_000L,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
    )

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        // The pull cursor is SharedPreferences-backed and survives between tests in a class.
        context.getSharedPreferences("checklists_pull_cursor", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a missing watermark asks for everything, never for nothing`() = runBlocking {
        val backend = StaticBackend(changes())

        ChecklistsSync.pull(context, backend)

        // Null, not "0" or an epoch string this client invented - `api/sync.parse_since` reads an
        // absent `since` as EPOCH, which is the "fetch everything" default both sides agree on.
        assertNull(backend.lastSince)
    }

    @Test
    fun `the watermark stored is the engine's own server_time, not a max over the rows`() = runBlocking {
        ChecklistsSync.pull(
            context,
            StaticBackend(
                changes(
                    checklists = listOf(remoteChecklist("c1", "sync-c1", "bio", updatedAtMs = 1_000L)),
                    serverTime = "2026-09-06T12:47:39.687402Z",
                ),
            ),
        )

        val next = StaticBackend(changes())
        ChecklistsSync.pull(context, next)

        // Microseconds intact - a watermark round-tripped through epoch millis would truncate, and
        // a truncated watermark either re-fetches harmlessly or skips a row forever.
        assertEquals("2026-09-06T12:47:39.687402Z", next.lastSince)
    }

    @Test
    fun `a server-only checklist is inserted`() = runBlocking {
        val report = ChecklistsSync.pull(
            context,
            StaticBackend(changes(checklists = listOf(remoteChecklist("c1", "sync-c1", "bio", 1_000L)))),
        )

        assertEquals(1, report.inserted)
        val stored = CarDatabase.getDatabase(context).checklistSyncDao().getAll().single()
        assertEquals("bio", stored.name)
        assertEquals("c1", stored.serverId)
        assertEquals("sync-c1", stored.syncId)
    }

    @Test
    fun `a local-only checklist the engine does not have survives completely untouched`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val id = db.checklistDao().insert(Checklist(name = "mine", syncId = "sync-mine", updatedAt = 500L))
        val before = db.checklistSyncDao().getByIdIncludingDeleted(id)

        ChecklistsSync.pull(context, StaticBackend(changes()))

        // Rule 5, by omission: absence from the engine is never evidence of deletion.
        assertEquals(before, db.checklistSyncDao().getByIdIncludingDeleted(id))
    }

    @Test
    fun `a newer local row is not overwritten by an older server row`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.checklistDao().insert(Checklist(name = "local newer", syncId = "sync-c1", updatedAt = 5_000L))

        val report = ChecklistsSync.pull(
            context,
            StaticBackend(changes(checklists = listOf(remoteChecklist("c1", "sync-c1", "server older", 1_000L)))),
        )

        assertEquals(1, report.skippedLocalNewer)
        assertEquals("local newer", db.checklistSyncDao().getAll().single().name)
    }

    @Test
    fun `a newer server row wins, and a tie goes to the server`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.checklistDao().insert(Checklist(name = "old", syncId = "sync-c1", updatedAt = 1_000L))

        ChecklistsSync.pull(
            context,
            StaticBackend(changes(checklists = listOf(remoteChecklist("c1", "sync-c1", "tie", 1_000L)))),
        )

        // Exactly equal resolves toward the server - the shared destination every device
        // converges on, same tiebreak EventsSync.pull states.
        assertEquals("tie", db.checklistSyncDao().getAll().single().name)
    }

    @Test
    fun `a server tombstone soft-deletes a matching local row, and one with no match inserts nothing`() =
        runBlocking {
            val db = CarDatabase.getDatabase(context)
            db.checklistDao().insert(Checklist(name = "doomed", syncId = "sync-c1", updatedAt = 1_000L))

            val report = ChecklistsSync.pull(
                context,
                StaticBackend(
                    changes(
                        checklists = listOf(
                            remoteChecklist("c1", "sync-c1", "doomed", 2_000L, deleted = true),
                            remoteChecklist("c2", "sync-c2", "never held here", 2_000L, deleted = true),
                        ),
                    ),
                ),
            )

            assertEquals(1, report.tombstoned)
            // The 88-row bug by name: a tombstone with nothing local to mark is inserted nowhere.
            assertEquals(1, report.skippedTombstoneNoLocalMatch)
            assertEquals(1, db.checklistSyncDao().getAll().size)
            assertTrue(db.checklistSyncDao().getAll().single().deleted)
        }

    @Test
    fun `a second consecutive pull of the same server state is a genuine no-op`() = runBlocking {
        val payload = changes(checklists = listOf(remoteChecklist("c1", "sync-c1", "bio", 1_000L)))
        ChecklistsSync.pull(context, StaticBackend(payload))

        val second = ChecklistsSync.pull(context, StaticBackend(payload))

        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
    }

    @Test
    fun `the engine's created_at never overwrites a local one`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.checklistDao().insert(
            Checklist(name = "bio", syncId = "sync-c1", createdAt = 1_000L, updatedAt = 1_000L),
        )

        ChecklistsSync.pull(
            context,
            StaticBackend(changes(checklists = listOf(remoteChecklist("c1", "sync-c1", "bio renamed", 2_000L)))),
        )

        val stored = db.checklistSyncDao().getAll().single()
        assertEquals("bio renamed", stored.name)
        // ChecklistSerializer makes created_at read-only, so the engine's value for a row this
        // phone pushed is the UPLOAD instant - and ChecklistController's trap-1 gate reads exactly
        // this column to decide which days may show history. Copying it back would erase a
        // checklist's own past.
        assertEquals(1_000L, stored.createdAt)
    }

    @Test
    fun `an item whose parent cannot be resolved is skipped and counted, never guessed at`() = runBlocking {
        val report = ChecklistsSync.pull(
            context,
            StaticBackend(
                changes(
                    items = listOf(
                        RemoteChecklistItem(
                            serverId = "i1",
                            syncId = "sync-i1",
                            checklistServerId = "a-checklist-this-device-has-never-seen",
                            text = "orphan",
                            sortOrder = 0,
                            createdAtMs = 1_000L,
                            updatedAtMs = 1_000L,
                            deleted = false,
                            measureUnit = null,
                            measureTarget = null,
                            measureDirection = null,
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, report.skippedOrphanedItem)
        assertEquals(0, report.inserted)
        assertTrue(CarDatabase.getDatabase(context).checklistItemSyncDao().getAll().isEmpty())
    }

    @Test
    fun `a tick matches on item and day, since the tick endpoint carries no sync id`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val checklistId = db.checklistDao().insert(
            Checklist(name = "bio", syncId = "sync-c1", serverId = "c1", updatedAt = 1_000L),
        )
        val itemId = db.checklistItemDao().insert(
            ChecklistItem(checklistId = checklistId, text = "squats", syncId = "sync-i1", serverId = "i1"),
        )

        val report = ChecklistsSync.pull(
            context,
            StaticBackend(
                changes(
                    ticks = listOf(
                        RemoteChecklistTick(
                            serverId = "t1",
                            // Null, exactly as the engine sends it - TickRequestSerializer has no
                            // sync_id field, so matching on one would never match anything.
                            syncId = null,
                            itemServerId = "i1",
                            day = 20_703,
                            tickedAtMs = 2_000L,
                            updatedAtMs = 2_000L,
                            deleted = false,
                            value = 8_400.0,
                            source = "USER_REPORTED",
                        ),
                    ),
                ),
            ),
        )

        assertEquals(1, report.inserted)
        val tick = db.checklistTickDao().getForItemOnDay(itemId, 20_703)
        assertNotNull(tick)
        assertEquals(8_400.0, tick!!.value!!, 0.001)
        assertEquals("t1", tick.serverId)
        assertFalse(tick.deleted)
    }
}
