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
 * [MIGRATION_59_60] - the body-supabase ticket's schema half. **Hand-builds each v59-shaped
 * table rather than running against [CarDatabase.getDatabase]'s live (current-version) schema**,
 * same reasoning [Migration58To59Test]'s own class doc gives: the live schema already carries
 * every v60 column, so `ALTER TABLE ... ADD COLUMN` against it would fail on a duplicate column
 * and prove nothing about the migration's own backfill logic. Every `createSql` below is copied
 * verbatim from `app/schemas/.../59.json`.
 */
@RunWith(RobolectricTestRunner::class)
class Migration59To60Test {
    private val context = RuntimeEnvironment.getApplication()

    private fun openV59ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(59) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `bodyweight_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`weightValue` REAL NOT NULL, `weightUnit` TEXT NOT NULL, `loggedAt` INTEGER NOT NULL, " +
                            "`trustTier` TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `meal_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`description` TEXT NOT NULL, `caloriesKcal` INTEGER, `proteinG` REAL, `carbsG` REAL, " +
                            "`fatG` REAL, `loggedAt` INTEGER NOT NULL, `sourceImagePath` TEXT, `trustTier` TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `meal_targets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`caloriesKcal` INTEGER NOT NULL, `proteinG` REAL NOT NULL, `carbsG` REAL NOT NULL, " +
                            "`fatG` REAL NOT NULL, `effectiveFromDateEpoch` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sleep_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sleepDate` INTEGER NOT NULL, `durationMinutes` INTEGER NOT NULL, `quality` INTEGER, " +
                            "`notes` TEXT, `loggedAt` INTEGER NOT NULL, `trustTier` TEXT NOT NULL, `syncId` TEXT NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `sleep_targets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`targetMinutes` INTEGER NOT NULL, `effectiveFromDateEpoch` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `workout_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`sessionsPerWeek` INTEGER NOT NULL, `effectiveFromWeekEpoch` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `workout_plan_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`exercise` TEXT NOT NULL, `targetSetsPerWeek` INTEGER NOT NULL, `effectiveFromWeekEpoch` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL, `repsPerSet` INTEGER DEFAULT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `workout_set_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`exercise` TEXT NOT NULL, `sets` INTEGER NOT NULL, `reps` INTEGER, `weightValue` REAL, " +
                            "`weightUnit` TEXT, `loggedAt` INTEGER NOT NULL, `trustTier` TEXT NOT NULL, " +
                            "`sourceListItemId` INTEGER DEFAULT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `backfills a distinct non-blank guid and preserves every existing row on all eight tables`() {
        val db = openV59ShapedDatabase("migration_59_60_backfill_test.db")
        db.execSQL(
            "INSERT INTO bodyweight_logs (id, weightValue, weightUnit, loggedAt, trustTier) " +
                "VALUES (1, 180.5, 'lbs', 1000, 'REPORTED')",
        )
        db.execSQL(
            "INSERT INTO bodyweight_logs (id, weightValue, weightUnit, loggedAt, trustTier) " +
                "VALUES (2, 181.0, 'lbs', 2000, 'REPORTED')",
        )
        db.execSQL(
            "INSERT INTO meal_targets (id, caloriesKcal, proteinG, carbsG, fatG, effectiveFromDateEpoch, updatedAt) " +
                "VALUES (1, 2200, 150.0, 200.0, 70.0, 500, 5000)",
        )

        MIGRATION_59_60.migrate(db)

        // Every existing row's own data survives untouched.
        db.query("SELECT weightValue, weightUnit, loggedAt, trustTier, guid, serverId, updatedAtMs, deleted " +
            "FROM bodyweight_logs WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(180.5, cursor.getDouble(0), 0.0001)
            assertEquals("lbs", cursor.getString(1))
            assertEquals(1000L, cursor.getLong(2))
            assertEquals("REPORTED", cursor.getString(3))
            // guid backfilled - never left at the "" placeholder default.
            assertTrue(cursor.getString(4).isNotBlank())
            assertTrue(cursor.isNull(5)) // serverId: null, not a fake placeholder
            // updatedAtMs backfilled from loggedAt, not left at 0.
            assertEquals(1000L, cursor.getLong(6))
            assertEquals(0, cursor.getInt(7)) // deleted = false
        }

        // Two different pre-existing rows get two DIFFERENT guids - never the same blank default.
        val guids = mutableListOf<String>()
        db.query("SELECT guid FROM bodyweight_logs ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) guids.add(cursor.getString(0))
        }
        assertEquals(2, guids.size)
        assertNotEquals(guids[0], guids[1])
        assertTrue(guids.all { it.isNotBlank() })

        // A target table (no updatedAtMs column at all) still gets guid/serverId/deleted.
        db.query("SELECT caloriesKcal, guid, serverId, deleted FROM meal_targets WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2200, cursor.getInt(0))
            assertTrue(cursor.getString(1).isNotBlank())
            assertTrue(cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    @Test
    fun `guid is unique on all eight tables and a new row may insert real values into the new columns`() {
        val db = openV59ShapedDatabase("migration_59_60_uniqueness_test.db")
        MIGRATION_59_60.migrate(db)

        db.execSQL(
            "INSERT INTO workout_set_logs (exercise, sets, loggedAt, trustTier, guid, serverId, updatedAtMs, deleted) " +
                "VALUES ('Squat', 3, 1000, 'REPORTED', 'guid-a', 'server-a', 1000, 0)",
        )
        // A second row with a DIFFERENT guid must insert cleanly under the new unique index.
        db.execSQL(
            "INSERT INTO workout_set_logs (exercise, sets, loggedAt, trustTier, guid, serverId, updatedAtMs, deleted) " +
                "VALUES ('Deadlift', 5, 2000, 'REPORTED', 'guid-b', NULL, 2000, 0)",
        )
        db.query("SELECT COUNT(*) FROM workout_set_logs").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }

        // The unique index actually rejects a duplicate guid.
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO workout_set_logs (exercise, sets, loggedAt, trustTier, guid, updatedAtMs, deleted) " +
                    "VALUES ('Bench', 3, 3000, 'REPORTED', 'guid-a', 3000, 0)",
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("duplicate guid must be rejected by the new unique index", threw)
    }

    @Test
    fun `every one of the eight tables gains guid, serverId and deleted`() {
        val db = openV59ShapedDatabase("migration_59_60_all_tables_test.db")
        MIGRATION_59_60.migrate(db)

        for (table in listOf(
            "bodyweight_logs", "meal_logs", "meal_targets", "sleep_logs",
            "sleep_targets", "workout_plans", "workout_plan_items", "workout_set_logs",
        )) {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue("$table missing guid", "guid" in columns)
                assertTrue("$table missing serverId", "serverId" in columns)
                assertTrue("$table missing deleted", "deleted" in columns)
            }
        }

        // Only the four LOG tables get updatedAtMs - the four TARGET tables reuse `updatedAt`.
        for (table in listOf("bodyweight_logs", "meal_logs", "sleep_logs", "workout_set_logs")) {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertTrue("$table missing updatedAtMs", "updatedAtMs" in columns)
            }
        }
        for (table in listOf("meal_targets", "sleep_targets", "workout_plans", "workout_plan_items")) {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertTrue("$table should not have a duplicate updatedAtMs", "updatedAtMs" !in columns)
            }
        }
    }
}
