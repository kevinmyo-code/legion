package com.kevin.legion.engine.migration

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.notes.NotesController
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
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
 * [EngineNotesRetirementCopy]'s own suite - ticket 15 step 4
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`). Mirrors
 * `PlaceControllerTest`'s ticket-15-step-1 section and `PantryControllerTest`'s ticket-15-step-2
 * section, adapted for the one thing THIS step lives or dies on that neither of those had to prove:
 * **id preservation**, tested directly rather than inferred from a passing count-based assertion -
 * see this file's own class doc for why (the exact blind spot `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`
 * names for `EventReplicaDao.upsert`'s pre-`b17bc88` defect: "a count-based check cannot see that
 * the thing it was protecting got replaced rather than preserved").
 */
@RunWith(RobolectricTestRunner::class)
class EngineNotesRetirementCopyTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // See NotesControllerTest's identically-worded @After for why this must run before
        // Robolectric's own per-method reset.
        RoomTestReset.drainArchDiskIoPool()
    }

    /** Writes a Notes `Item` record directly through the engine, bypassing [NotesController]
     * entirely - simulates an item created before ticket 15 step 4's repoint (or on an install
     * still mid-soak), which only [EngineNotesRetirementCopy] should ever be able to see and copy
     * forward. Returns the engine record's own id - the value [EngineNotesRetirementCopy] must
     * seat the copied row at, per the step's own ID CONTRACT. */
    private suspend fun createEngineItem(text: String, startsAt: Long? = null): Long {
        val db = CarDatabase.getDatabase(context)
        val schema = NotesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val result = store.create(
            recordTypeId = schema.recordTypeId,
            fieldValues = mapOf(
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_TEXT) to text,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE) to false,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_SORT_ORDER) to 0,
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_STARTS_AT) to startsAt,
            ),
            provenance = RecordProvenance.USER,
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    /** Same shape as [createEngineItem], for a Dates `Event` record. */
    private suspend fun createEngineEvent(title: String, startsAt: Long): Long {
        val db = CarDatabase.getDatabase(context)
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val result = store.create(
            recordTypeId = schema.recordTypeId,
            fieldValues = mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to title,
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to startsAt,
            ),
            provenance = RecordProvenance.USER,
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    // ------------------------------------------------------------------------------ id preservation

    @Test
    fun `a copied item is seated at its OWN engine records id, not a fresh one - THE id contract`() = runBlocking {
        // Seed a second, unrelated item FIRST so a naive autoincrement copy would land the first
        // item at local id 2, not its real engine id - this is what makes the assertion below
        // capable of catching a regression rather than passing by coincidence.
        createEngineItem("filler")
        val engineId = createEngineItem("alarm-bound item")

        val result = EngineNotesRetirementCopy.copyIfNeeded(context)
        assertEquals(2, result.itemsCopied)

        val row = CarDatabase.getDatabase(context).eventDao().getById(engineId)
        assertEquals(
            "the copied row's local id must equal the engine record's own records.id - this is the whole point of the step",
            engineId,
            row?.id,
        )
        assertEquals("alarm-bound item", row?.title)
    }

    @Test
    fun `MUTATION PROOF - seating at a fresh autoincrement id instead of the carried one fails the id assertion`() = runBlocking {
        // This test does not call the production copier - it simulates the REGRESSION the id
        // contract exists to prevent (a naive copy that autoincrements instead of carrying the
        // engine id), and asserts that regression is DETECTABLE, i.e. that the assertion above is
        // not satisfiable by coincidence. If this test ever fails, the real test above has gone
        // blind the same way EventReplicaDao.upsert's pre-b17bc88 count-only test did.
        createEngineItem("filler")
        val engineId = createEngineItem("alarm-bound item")

        val db = CarDatabase.getDatabase(context)
        // Naive autoincrement insert - id = 0, exactly what a copier WITHOUT the id-carry contract
        // would have done.
        val wrongId = db.eventDao().insert(
            com.kevin.legion.data.local.Event(
                id = 0,
                serverId = java.util.UUID.randomUUID().toString(),
                title = "alarm-bound item",
                startsAt = null,
                source = "legion",
                kind = EventKind.REMINDER,
                updatedAtMs = 1L,
                createdAt = 1L,
            ),
        )

        assertFalse(
            "a naive autoincremented id must NOT equal the engine's own records.id - proves the real assertion is load-bearing",
            wrongId == engineId,
        )
    }

    @Test
    fun `itemById reads the copied item back at the carried id through NotesController itself`() = runBlocking {
        val engineId = createEngineItem("read back through the controller")

        EngineNotesRetirementCopy.copyIfNeeded(context)

        val reread = NotesController.itemById(context, engineId)
        assertEquals("read back through the controller", reread?.text)
        assertEquals(engineId, reread?.id)
    }

    // ------------------------------------------------------------------------------ idempotency

    @Test
    fun `the one-time copy moves engine Items into events, idempotently`() = runBlocking {
        createEngineItem("buy milk")

        val first = EngineNotesRetirementCopy.copyIfNeeded(context)
        assertEquals(1, first.itemsCopied)
        assertFalse(first.alreadyDone)

        val afterFirst = CarDatabase.getDatabase(context).eventDao().getAllActive()
        assertEquals(1, afterFirst.size)
        assertEquals("buy milk", afterFirst.single().title)

        // Running it again changes nothing - both the fast-path completion flag AND, independently,
        // the per-id occupancy check (if the flag were ever cleared) must be no-ops the second time.
        val second = EngineNotesRetirementCopy.copyIfNeeded(context)
        assertEquals(0, second.itemsCopied)
        assertEquals(0, second.eventsCopied)
        assertTrue(second.alreadyDone)
        assertEquals(1, CarDatabase.getDatabase(context).eventDao().getAllActive().size)
    }

    // ------------------------------------------------------------------------------ kind

    @Test
    fun `a Notes Item is copied with kind reminder, a Dates Event with kind appointment`() = runBlocking {
        createEngineItem("todo")
        createEngineEvent("Dentist", startsAt = 50_000L)

        EngineNotesRetirementCopy.copyIfNeeded(context)

        val rows = CarDatabase.getDatabase(context).eventDao().getAll()
        assertEquals(2, rows.size)
        assertEquals(EventKind.REMINDER, rows.single { it.title == "todo" }.kind)
        assertEquals(EventKind.EVENT, rows.single { it.title == "Dentist" }.kind)
    }

    // ------------------------------------------------------------------------------ deletes nothing

    @Test
    fun `the engine's Item and Event records still exist after the copy - nothing is deleted`() = runBlocking {
        createEngineItem("todo")
        createEngineEvent("Dentist", startsAt = 50_000L)
        EngineNotesRetirementCopy.copyIfNeeded(context)

        val db = CarDatabase.getDatabase(context)
        val notesSchema = NotesAspectSeeder.ensureSeeded(context)
        val datesSchema = DatesAspectSeeder.ensureSeeded(context)
        assertEquals(
            "ticket 15: nothing is deleted until every aspect is repointed and soaked",
            1,
            db.engineRecordDao().activeByRecordType(notesSchema.recordTypeId).size,
        )
        assertEquals(
            "ticket 15: nothing is deleted until every aspect is repointed and soaked",
            1,
            db.engineRecordDao().activeByRecordType(datesSchema.recordTypeId).size,
        )
    }

    @Test
    fun `unconfigured reads return engine-only items after the repoint`() = runBlocking {
        // Nothing ever calls NotesController.addItem here - this item exists ONLY in the engine,
        // simulating data written before ticket 15 step 4's repoint landed. allItems must still
        // surface it.
        createEngineItem("engine-only item")

        val items = NotesController.allItems(context)
        assertEquals(1, items.size)
        assertEquals("engine-only item", items.single().text)
    }
}
