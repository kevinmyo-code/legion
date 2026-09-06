package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineSyncNow
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        /** Item server ids whose ticks the engine refuses with a 400, keyed the way the real
         * engine keys its own refusal: by the ITEM, since the rule is a property of the item's
         * `measure_unit`. */
        val refusedTicksForItems = mutableSetOf<String>()

        /** Set to make every call fail the way an unreachable laptop engine does - the branch that
         * must still STOP a table (rule 5), as distinct from a refusal (rule 6). */
        var unreachable = false

        override suspend fun fetchChanges(sinceIso: String?) =
            Result.success(ChecklistChanges("2026-09-06T00:00:00Z", emptyList(), emptyList(), emptyList()))

        override suspend fun upsertChecklist(syncId: String, fields: ChecklistFields): Result<RemoteChecklist> {
            if (unreachable) return Result.failure(unreachableFailure())
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
            if (unreachable) return Result.failure(unreachableFailure())
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
            val refusal = when {
                unreachable -> unreachableFailure()
                itemServerId in refusedTicksForItems -> measuredTickRefusal()
                else -> null
            }
            if (refusal != null) return Result.failure(refusal)
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

        /** The engine's ACTUAL refusal, copied from the A25 on 2026-09-06 - DRF's
         * `{"non_field_errors": [...]}` envelope around `checklists/serializers.py`'s own sentence,
         * which is itself verbatim from `ChecklistController.tick`. Hand-shortening it would be
         * testing a sentence nobody sends. */
        private fun measuredTickRefusal() = EngineHttpException(
            EngineFailure.Refused(
                status = 400,
                body = """{"non_field_errors":["\"3 sets goblet squats\" is measured in kg - """ +
                    """give a number to tick it, nothing was recorded."]}""",
            ),
        )

        private fun unreachableFailure() = EngineHttpException(
            EngineFailure.Unreachable(host = "192.168.1.117:8000", message = "connection refused"),
        )
    }

    private lateinit var backend: RecordingBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        // The cursor is SharedPreferences-backed and outlives the database reset above; without
        // this, a cursor left by an earlier test in this class skips the rows a later one seeds.
        ChecklistsBackfillCursor.resetForTest(context)
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
        assertTrue(first.stopped.isEmpty())
        assertTrue(first.skipped.isEmpty())

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

    // ------------------------------------------------------------------ rule 6: a refused row

    /** Two checklists, each with one item and one tick. `bio`'s item is the measured one whose
     * legacy tick carries no value, exactly as on the A25. */
    private suspend fun seedRefusedAndFine(): Long {
        val db = CarDatabase.getDatabase(context)
        val bio = db.checklistDao().insert(Checklist(name = "bio", syncId = "sync-bio"))
        val squats = db.checklistItemDao().insert(
            ChecklistItem(checklistId = bio, text = "3 sets goblet squats", syncId = "sync-squats"),
        )
        db.checklistTickDao().insert(ChecklistTick(itemId = squats, day = 20_700, syncId = "sync-squats-tick"))

        val errands = db.checklistDao().insert(Checklist(name = "errands", syncId = "sync-errands"))
        val post = db.checklistItemDao().insert(
            ChecklistItem(checklistId = errands, text = "post the parcel", syncId = "sync-post"),
        )
        db.checklistTickDao().insert(ChecklistTick(itemId = post, day = 20_700, syncId = "sync-post-tick"))

        backend.refusedTicksForItems += "srv-sync-squats"
        return squats
    }

    @Test
    fun `a refused tick is skipped and every other tick still crosses`() = runBlocking {
        // The A25 defect: the ticks table stopped dead on its first refusal, so ZERO ticks were
        // ever backfilled and the blob reprinted on every sync. The refusal itself is correct -
        // the tick predates the item being measured - but it may not hold the others hostage.
        seedRefusedAndFine()

        val report = ChecklistsBackfill.run(context, backend)

        assertEquals(listOf("srv-sync-post" to 20_700), backend.tickPushes)
        assertEquals(1, report.skipped.size)
        assertEquals(OutboxTarget.CHECKLIST_TICKS, report.skipped.single().table)
        // The engine's own words, unwrapped out of DRF's envelope - not the raw JSON, and not a
        // paraphrase either.
        assertEquals(
            "\"3 sets goblet squats\" is measured in kg - give a number to tick it, nothing was recorded.",
            report.skipped.single().reason,
        )
        // Four rows (two checklists, two items) plus the one tick that was taken.
        assertEquals(5, report.pushed)
        assertTrue("a refusal is not a stop", report.stopped.isEmpty())
    }

    @Test
    fun `a refused row is never retried, and later runs still say it is being kept back`() = runBlocking {
        seedRefusedAndFine()
        ChecklistsBackfill.run(context, backend)

        val second = ChecklistsBackfill.run(context, backend)

        // Nothing re-attempted: the high-water cursor advanced past it, which is what stops the
        // "error blob on every sync" without needing a flag on the row itself.
        assertEquals(1, backend.tickPushes.size)
        assertTrue(second.skipped.isEmpty())
        // But the fact survives the run that produced it, so a later sentence can still say it.
        assertEquals(1, second.unsyncableTotal)
    }

    @Test
    fun `the refused tick is still on the phone - nothing deletes the user's history`() = runBlocking {
        val squats = seedRefusedAndFine()

        ChecklistsBackfill.run(context, backend)

        // Kevin's ruling: "keep it locally, never send it". He did the squats; a tombstone here
        // would assert he did not.
        val tick = CarDatabase.getDatabase(context).checklistTickDao().getForItemOnDay(squats, 20_700)
        assertNotNull(tick)
        assertFalse(tick!!.deleted)
    }

    @Test
    fun `an unreachable engine still STOPS the table - a refusal and an outage are not the same`() = runBlocking {
        seedPreSyncRows("bio")
        backend.unreachable = true

        val report = ChecklistsBackfill.run(context, backend)

        assertEquals(0, report.pushed)
        assertTrue(report.skipped.isEmpty())
        // One stop per table, and the run resumes from the cursor next time rather than recording
        // the row as permanently unsendable.
        assertEquals(3, report.stopped.size)
        assertEquals(0, report.unsyncableTotal)
    }

    // ------------------------------------------------------------------ the sentence Kevin reads

    @Test
    fun `the summary sentence names the skip in words, never as a JSON blob`() = runBlocking {
        seedRefusedAndFine()
        val report = ChecklistsBackfill.run(context, backend)

        val phrase = EngineSyncNow(context).backfillPhrase(report)

        assertEquals(
            "backfilled 5, skipped 1 (kept on this phone, never sent: " +
                "\"3 sets goblet squats\" is measured in kg - give a number to tick it, nothing was recorded)",
            phrase,
        )
        // The old line read `Backfill stopped: checklist_ticks: {"non_field_errors":[...]}`.
        assertFalse(phrase.contains("non_field_errors"))
        assertFalse(phrase.contains("Backfill stopped"))
    }

    @Test
    fun `a later run still says a tick is being held back rather than reporting a clean pass`() = runBlocking {
        seedRefusedAndFine()
        ChecklistsBackfill.run(context, backend)
        val second = ChecklistsBackfill.run(context, backend)

        assertEquals(
            "backfilled 0 (1 the engine will not take, kept on this phone and never sent)",
            EngineSyncNow(context).backfillPhrase(second),
        )
    }

    @Test
    fun `a clean run says only what it did`() = runBlocking {
        seedPreSyncRows("bio")
        val report = ChecklistsBackfill.run(context, backend)

        assertEquals("backfilled 3", EngineSyncNow(context).backfillPhrase(report))
    }
}
