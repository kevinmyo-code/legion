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
        // A throwaway earlier engine record so the two tables' independent AUTOINCREMENT counters
        // cannot coincidentally agree on the same number - this test needs to prove the id genuinely
        // CHANGED, not merely that it happens to look the same on a freshly-reset database.
        db.engineRecordDao().insert(
            com.kevin.legion.data.local.EngineRecord(
                recordTypeId = NotesAspectSeeder.ensureSeeded(context).recordTypeId,
                createdAt = now, updatedAt = now, provenance = com.kevin.legion.data.local.RecordProvenance.USER,
            ),
        )
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
}
