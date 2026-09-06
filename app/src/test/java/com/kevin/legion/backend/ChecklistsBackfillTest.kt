package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ChecklistsBackfill] - the one-time upload of every checklist row that predates write-through.
 * The A25 holds four checklists (`bio`, `errands`, `Todo`, `Groceries`) and the engine's own
 * checklist tables were measured empty on 2026-09-06, so this path is the only route any of that
 * has to the engine.
 *
 * The property this file pins down is the one the brief names: **every local row is sent exactly
 * once, and a second run sends nothing.** Both halves matter - a backfill that re-sends is
 * harmless only because every push is idempotent server-side, and leaning on that instead of the
 * cursor would mean a full rescan-and-re-push on every foreground return forever.
 */
@RunWith(RobolectricTestRunner::class)
class ChecklistsBackfillTest {

    private val context = RuntimeEnvironment.getApplication()

    /** Records every push and hands back a plausible server row, so the backfill can write the
     * `serverId` back and the next table's parent lookup can find it. */
    private class RecordingBackend : ChecklistsBackend {
        val checklistPushes = mutableListOf<String>()
        val itemPushes = mutableListOf<String>()
        val tickPushes = mutableListOf<Pair<String, Int>>()

        override suspend fun fetchChanges(sinceIso: String?) =
            Result.success(ChecklistChanges("2026-09-06T00:00:00Z", emptyList(), emptyList(), emptyList()))

        override suspend fun upsertChecklist(syncId: String, fields: ChecklistFields): Result<RemoteChecklist> {
            checklistPushes += syncId
            return Result.success(
                RemoteChecklist(
                    serverId = "srv-$syncId",
                    syncId = syncId,
                    name = fields.name,
                    scheduleKind = fields.scheduleKind,
                    scheduleEvery = fields.scheduleEvery,
                    scheduleDaysOfWeek = fields.scheduleDaysOfWeek,
                    sortOrder = fields.sortOrder,
                    archived = fields.archived,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                    deleted = false,
                ),
            )
        }

        override suspend fun patchChecklist(serverId: String, syncId: String, fields: ChecklistFields) =
            upsertChecklist(syncId, fields)

        override suspend fun deleteChecklist(serverId: String) = Result.success(true)

        override suspend fun upsertItem(
            checklistServerId: String,
            syncId: String,
            fields: ChecklistItemFields,
        ): Result<RemoteChecklistItem> {
            itemPushes += syncId
            return Result.success(
                RemoteChecklistItem(
                    serverId = "srv-$syncId",
                    syncId = syncId,
                    checklistServerId = checklistServerId,
                    text = fields.text,
                    sortOrder = fields.sortOrder,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                    deleted = false,
                    measureUnit = fields.measureUnit,
                    measureTarget = fields.measureTarget,
                    measureDirection = fields.measureDirection,
                ),
            )
        }

        override suspend fun patchItem(
            checklistServerId: String,
            itemServerId: String,
            syncId: String,
            fields: ChecklistItemFields,
        ) = upsertItem(checklistServerId, syncId, fields)

        override suspend fun deleteItem(checklistServerId: String, itemServerId: String) = Result.success(true)

        override suspend fun tick(
            checklistServerId: String,
            itemServerId: String,
            day: Int,
            value: Double?,
            source: String,
        ): Result<RemoteChecklistTick> {
            tickPushes += itemServerId to day
            return Result.success(
                RemoteChecklistTick(
                    serverId = "srv-tick-$itemServerId-$day",
                    syncId = null,
                    itemServerId = itemServerId,
                    day = day,
                    tickedAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                    deleted = false,
                    value = value,
                    source = source,
                ),
            )
        }

        override suspend fun untick(checklistServerId: String, itemServerId: String, day: Int) = Result.success(true)
    }

    private lateinit var backend: RecordingBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = RecordingBackend()
    }

    /** A checklist, one item and one tick, all written straight to Room - i.e. exactly the shape a
     * row created before this ticket existed has: no `serverId` anywhere. */
    private suspend fun seedPreSyncRows(name: String): Triple<Long, Long, Int> {
        val db = CarDatabase.getDatabase(context)
        val checklistId = db.checklistDao().insert(Checklist(name = name, syncId = "sync-$name"))
        val itemId = db.checklistItemDao().insert(
            ChecklistItem(checklistId = checklistId, text = "$name item", syncId = "sync-$name-item"),
        )
        db.checklistTickDao().insert(
            ChecklistTick(itemId = itemId, day = 20_700, syncId = "sync-$name-tick"),
        )
        return Triple(checklistId, itemId, 20_700)
    }

    @Test
    fun `every pre-write-through row is sent once, and the second run sends nothing`() = runBlocking {
        seedPreSyncRows("bio")
        seedPreSyncRows("errands")

        val first = ChecklistsBackfill.run(context, backend)

        assertEquals(listOf("sync-bio", "sync-errands"), backend.checklistPushes)
        assertEquals(listOf("sync-bio-item", "sync-errands-item"), backend.itemPushes)
        assertEquals(2, backend.tickPushes.size)
        // Six rows across three tables, every one pushed exactly once.
        assertEquals(6, first.pushed)
        assertTrue(first.failed.isEmpty())

        val second = ChecklistsBackfill.run(context, backend)

        // A genuine no-op: nothing new on the wire, nothing counted as pushed.
        assertEquals(2, backend.checklistPushes.size)
        assertEquals(2, backend.itemPushes.size)
        assertEquals(2, backend.tickPushes.size)
        assertEquals(0, second.pushed)
        assertEquals(0, second.alreadyPresent) // the CURSOR skipped them; nothing was even examined
    }

    @Test
    fun `a successful push writes the engine's own id back to the local row`() = runBlocking {
        val (checklistId, itemId, day) = seedPreSyncRows("bio")

        ChecklistsBackfill.run(context, backend)

        val db = CarDatabase.getDatabase(context)
        // The parent's serverId is not bookkeeping - every item and tick route is nested under it
        // (checklists/urls.py), so a child cannot be addressed at all until this is written.
        assertEquals("srv-sync-bio", db.checklistSyncDao().getByIdIncludingDeleted(checklistId)!!.serverId)
        assertEquals("srv-sync-bio-item", db.checklistItemDao().getByIdIncludingDeleted(itemId)!!.serverId)
        assertNotNull(db.checklistTickDao().getForItemOnDay(itemId, day)!!.serverId)
    }

    @Test
    fun `a row that already carries a serverId is skipped, not re-pushed`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.checklistDao().insert(Checklist(name = "already", syncId = "sync-already", serverId = "srv-existing"))

        val report = ChecklistsBackfill.run(context, backend)

        assertTrue(backend.checklistPushes.isEmpty())
        assertEquals(0, report.pushed)
        assertEquals(1, report.alreadyPresent)
    }

    @Test
    fun `a locally-deleted row that never synced is skipped, never resurrected`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.checklistDao().insert(Checklist(name = "gone", syncId = "sync-gone", deleted = true))

        val report = ChecklistsBackfill.run(context, backend)

        // Pushing it would create it on the engine only to tombstone it again next pass, and a
        // race between the two would leave it alive.
        assertTrue(backend.checklistPushes.isEmpty())
        assertEquals(1, report.skippedLocalOnlyDeleted)
    }
}
