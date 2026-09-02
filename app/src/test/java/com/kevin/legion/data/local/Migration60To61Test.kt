package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_60_61] - the memory-supabase ticket's schema half. **Hand-builds each v60-shaped
 * table rather than running against [CarDatabase.getDatabase]'s live (current-version) schema**,
 * same reasoning [Migration59To60Test]'s own class doc gives: the live schema already carries
 * every v61 column, so `ALTER TABLE ... ADD COLUMN` against it would fail on a duplicate column
 * and prove nothing about the migration's own backfill logic.
 */
@RunWith(RobolectricTestRunner::class)
class Migration60To61Test {
    private val context = RuntimeEnvironment.getApplication()

    private fun openV60ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(60) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `syncId` TEXT NOT NULL DEFAULT '')",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `companion_memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`vehicleId` TEXT NOT NULL, `text` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                            "`importance` INTEGER NOT NULL DEFAULT 5, `createdAt` INTEGER NOT NULL, " +
                            "`lastAccessedAt` INTEGER NOT NULL DEFAULT 0, `embeddingVector` TEXT, `embeddingModel` TEXT, " +
                            "`syncId` TEXT NOT NULL DEFAULT '')",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `memory_audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`event` TEXT NOT NULL, `store` TEXT NOT NULL, `detail` TEXT NOT NULL, " +
                            "`refId` INTEGER NOT NULL DEFAULT 0, `vehicleId` TEXT NOT NULL DEFAULT '', `at` INTEGER NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `backfills a blank syncId and preserves every existing row on all three tables`() {
        val db = openV60ShapedDatabase("migration_60_61_backfill_test.db")
        // A legacy row with a genuinely blank syncId (pre-dates the column ever being populated).
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (1, 'work address', 1000, '')")
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (2, 'gym address', 2000, 'already-has-one')")
        db.execSQL(
            "INSERT INTO companion_memories (id, vehicleId, text, category, source, createdAt) " +
                "VALUES (1, 'jeep', 'likes jazz', 'driver', 'consolidated', 5000)",
        )
        db.execSQL(
            "INSERT INTO memory_audit (id, event, store, detail, at) " +
                "VALUES (1, 'written', 'memories', 'work address', 1000)",
        )

        MIGRATION_60_61.migrate(db)

        // memories: blank syncId backfilled, non-blank one left untouched; updatedAtMs backfilled
        // from timestamp.
        db.query("SELECT syncId, serverId, updatedAtMs, deleted FROM memories WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
            assertTrue(cursor.isNull(1))
            assertEquals(1000L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
        }
        db.query("SELECT syncId FROM memories WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("already-has-one", cursor.getString(0))
        }

        // companion_memories: syncId backfilled (was blank), updatedAtMs backfilled from createdAt.
        db.query("SELECT syncId, updatedAtMs, deleted FROM companion_memories WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
            assertEquals(5000L, cursor.getLong(1))
            assertEquals(0, cursor.getInt(2))
        }

        // memory_audit: a fresh guid minted, updatedAtMs backfilled from at.
        db.query("SELECT guid, serverId, updatedAtMs, deleted FROM memory_audit WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).isNotBlank())
            assertTrue(cursor.isNull(1))
            assertEquals(1000L, cursor.getLong(2))
            assertEquals(0, cursor.getInt(3))
        }

        // Original data survives untouched.
        db.query("SELECT text FROM memories WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("work address", cursor.getString(0))
        }
    }

    @Test
    fun `two pre-existing blank-syncId rows get two DIFFERENT backfilled values`() {
        val db = openV60ShapedDatabase("migration_60_61_uniqueness_test.db")
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (1, 'a', 1000, '')")
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (2, 'b', 2000, '')")

        MIGRATION_60_61.migrate(db)

        val syncIds = mutableListOf<String>()
        db.query("SELECT syncId FROM memories ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) syncIds.add(cursor.getString(0))
        }
        assertEquals(2, syncIds.size)
        assertNotEquals(syncIds[0], syncIds[1])
        assertTrue(syncIds.all { it.isNotBlank() })
    }

    @Test
    fun `v61 no longer creates a unique index on syncId or guid - a duplicate insert is accepted`() {
        // CORRECTED 2026-09-02: this test used to assert the opposite (a duplicate syncId gets
        // rejected). That assertion went stale the moment MIGRATION_60_61 was rewritten as a table
        // rebuild from v61's generated createSql - MemoryEntry/CompanionMemory/MemoryAudit declared
        // no @Entity(indices = ...) at the time, so 61.json carried no index and the rebuild created
        // none. The uniqueness guarantee was silently lost while this test kept passing, because it
        // was asserting the migration's OWN output rather than a real constraint. The coverage this
        // used to provide has NOT been dropped - it moved to
        // `Migration61To62Test`'s "re-creates the unique index MIGRATION_60_61 lost..." test, which
        // asserts the index exists (and rejects a duplicate) after MIGRATION_61_62, the migration
        // that actually creates it now that the entities declare it. This test instead documents
        // v61's real, narrower guarantee: rows survive the rebuild and a duplicate is NOT (yet)
        // rejected at the database level.
        val db = openV60ShapedDatabase("migration_60_61_no_index_test.db")
        MIGRATION_60_61.migrate(db)

        db.execSQL(
            "INSERT INTO memories (text, timestamp, syncId, updatedAtMs, deleted) " +
                "VALUES ('a', 1000, 'guid-a', 1000, 0)",
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO memories (text, timestamp, syncId, updatedAtMs, deleted) " +
                    "VALUES ('b', 2000, 'guid-a', 2000, 0)",
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("v61 has no unique index yet, so a duplicate syncId must be ACCEPTED", !threw)
    }

    @Test
    fun `every one of the three tables gains serverId, updatedAtMs and deleted, and memory_audit gains guid`() {
        val db = openV60ShapedDatabase("migration_60_61_all_tables_test.db")
        MIGRATION_60_61.migrate(db)

        for (table in listOf("memories", "companion_memories", "memory_audit")) {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertTrue("$table missing serverId", "serverId" in columns)
                assertTrue("$table missing updatedAtMs", "updatedAtMs" in columns)
                assertTrue("$table missing deleted", "deleted" in columns)
            }
        }

        db.query("PRAGMA table_info(`memory_audit`)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            assertTrue("memory_audit missing guid", "guid" in columns)
        }
    }
}
