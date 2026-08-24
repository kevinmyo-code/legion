package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.ListItemSkip
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Cutover 1's one-time catch-up (`docs/architecture/cutover1-2026-08-24.md`, ticket 22 point 3):
 * [EngineDataMigrationWave1.catchUpOnce] re-scans for legacy rows written after wave 1's own first
 * pass, and rekeys every [ListItemSkip] onto its item's new engine record id.
 */
@RunWith(RobolectricTestRunner::class)
class EngineDataMigrationWave1CutoverTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("engine_migration_wave1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private suspend fun seedList(): Long {
        val now = System.currentTimeMillis()
        return db.itemListDao().insert(ItemList(name = "List", tickable = true, lastUsedAt = now, createdAt = now, updatedAt = now))
    }

    @Test
    fun `catchUpOnce picks up a legacy row written after wave 1's own first pass`() = runBlocking {
        val listId = seedList()
        val now = System.currentTimeMillis()
        db.listItemDao().insert(ListItem(listId = listId, text = "first pass item", createdAt = now, updatedAt = now))

        // Wave 1's ordinary first pass (as if this install ran it before cutover shipped).
        EngineDataMigrationWave1.copyNotesIfNeeded(context)
        val schema = NotesAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.recordTypeId).size)

        // A row written to legacy in the window between wave 1 landing and this cutover install -
        // simulated here since no code path writes list_items anymore post-cutover, but the DAO
        // itself still physically can (this is exactly the historical-data scenario catchUpOnce
        // exists for, not a live write path).
        db.listItemDao().insert(ListItem(listId = listId, text = "late-arriving item", createdAt = now + 1, updatedAt = now + 1))

        val ran = EngineDataMigrationWave1.catchUpOnce(context)
        assertTrue("the first catch-up call must actually run, not report already-done", ran)

        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.recordTypeId).size)
    }

    @Test
    fun `catchUpOnce only ever forces a rescan once`() = runBlocking {
        val listId = seedList()
        val now = System.currentTimeMillis()
        db.listItemDao().insert(ListItem(listId = listId, text = "one item", createdAt = now, updatedAt = now))

        val first = EngineDataMigrationWave1.catchUpOnce(context)
        val second = EngineDataMigrationWave1.catchUpOnce(context)

        assertTrue(first)
        assertTrue("a second call must be a no-op, guarded by its own completion marker", !second)
    }

    @Test
    fun `catchUpOnce rekeys a skip row onto its item's new engine record id`() = runBlocking {
        val listId = seedList()
        val now = System.currentTimeMillis()
        // A throwaway, DELETED legacy item consumes a `list_items` AUTOINCREMENT slot without
        // producing an engine record at all (wave 1's copier skips tombstoned rows - see
        // EngineDataMigrationWave1's own "does not copy tombstoned rows" doc) - shifting the legacy
        // id space forward relative to the engine's `records` table, so this fixture's real item
        // cannot coincidentally collide the way should-fix 1's own collision-guard test below
        // deliberately forces. (An earlier version of this fixture inserted a throwaway ENGINE
        // record directly instead, which - after the should-fix 1 guard landed - made the fixture
        // indistinguishable from a genuine collision and the guard correctly refused to touch it,
        // failing this test for the right reason applied to the wrong fixture.)
        val throwawayLegacyId = db.listItemDao().insert(ListItem(listId = listId, text = "throwaway", createdAt = now, updatedAt = now))
        db.listItemDao().deleteById(throwawayLegacyId, now)
        val legacyItemId = db.listItemDao().insert(
            ListItem(listId = listId, text = "weekly trash day", repeatKind = "WEEKLY", repeatEvery = 1, createdAt = now, updatedAt = now),
        )
        val legacyItem = db.listItemDao().getById(legacyItemId)!!
        val skipDate = now
        db.listItemSkipDao().insert(ListItemSkip(itemId = legacyItemId, skippedDate = skipDate))

        EngineDataMigrationWave1.catchUpOnce(context)

        val engineRecord = db.engineRecordDao().getByGuid(legacyItem.syncId)!!
        assertNotEquals("the item's engine id is a fresh AUTOINCREMENT value, not the legacy one", legacyItemId, engineRecord.id)

        val skips = db.listItemSkipDao().skippedDatesForItem(engineRecord.id)
        assertEquals("the skip row must now be found by the ENGINE record's id", listOf(skipDate), skips)
        assertTrue(
            "the skip must no longer be findable under the stale legacy id",
            db.listItemSkipDao().skippedDatesForItem(legacyItemId).isEmpty(),
        )
    }

    @Test
    fun `an already-correct engine-keyed skip is never misdirected by an id-space collision with an unrelated legacy row`() = runBlocking {
        // Senior review, 2026-08-24 (should-fix 1): rekeySkipsToEngineIds must check liveness
        // against the Notes engine record type BEFORE ever consulting the legacy table - otherwise
        // an already-correct, post-cutover, engine-keyed skip whose itemId numerically collides
        // with an UNRELATED legacy list_items id gets misdirected onto that unrelated row.
        val now = System.currentTimeMillis()
        val schema = NotesAspectSeeder.ensureSeeded(context)

        // A real Notes engine record for "item A", created directly (not via the legacy copier) -
        // the `records` table's own AUTOINCREMENT sequence gives it id 1, its very first row.
        val store = com.kevin.legion.engine.RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val created = store.create(
            recordTypeId = schema.recordTypeId,
            fieldValues = mapOf(
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_TEXT) to "item A",
                schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE) to false,
            ),
            provenance = com.kevin.legion.data.local.RecordProvenance.USER,
            now = now,
        )
        val engineIdA = (created as com.kevin.legion.engine.RecordStore.WriteResult.Success).recordId

        // A skip already correctly keyed to item A's real engine id - exactly what a post-cutover
        // NotesController.skipOccurrence would have written.
        val skipDate = now
        db.listItemSkipDao().insert(ListItemSkip(itemId = engineIdA, skippedDate = skipDate))

        // An UNRELATED legacy item B - `list_items`' own AUTOINCREMENT sequence is independent of
        // `records`', so its very first row ALSO gets id 1, forcing the collision this test exists
        // to exercise.
        val listId = seedList()
        val legacyItemBId = db.listItemDao().insert(
            ListItem(listId = listId, text = "item B", createdAt = now, updatedAt = now),
        )
        assertEquals("the fixture only proves anything if the two id spaces genuinely collide", engineIdA, legacyItemBId)

        EngineDataMigrationWave1.catchUpOnce(context)

        // The skip must still be found under item A's real engine id, completely undisturbed - not
        // rekeyed onto whatever item B's own guid happened to resolve to (or orphaned as a false
        // negative because item B's syncId matched nothing).
        assertEquals(
            "an already-correct skip must never be touched by the legacy-lookup path at all",
            listOf(skipDate),
            db.listItemSkipDao().skippedDatesForItem(engineIdA),
        )
    }
}
