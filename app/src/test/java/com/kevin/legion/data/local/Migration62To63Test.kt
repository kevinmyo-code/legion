package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_62_63] - live-sync's last aspect slice's schema half (`goals`, `grocery_staples`,
 * `item_lists`, `list_items`).
 *
 * Same "read the LIVE generated `63.json` at run time" discipline [Migration61To62Test] established
 * - see that class's own doc comment for why a hand-transcribed comparison is not good enough.
 */
@RunWith(RobolectricTestRunner::class)
class Migration62To63Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String)

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private val schemaJson: SchemaFile by lazy {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/63.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/63.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/63.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so kapt emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v63 schema."
        }

    private data class ColumnInfo(val name: String, val type: String, val notNull: Int, val dfltValue: String?, val pk: Int)

    private fun readColumns(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(
                    ColumnInfo(
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        dfltValue = if (cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) null else cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
                        pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                    ),
                )
            }
        }
        return columns.sortedBy { it.name }
    }

    private fun assertTableMatchesGeneratedSchema(migratedDb: SupportSQLiteDatabase, tableName: String) {
        val entity = generatedEntity(tableName)
        val referenceTable = "${tableName}_generated_reference"
        val referenceCreateSql = entity.createSql.replace("\${TABLE_NAME}", referenceTable)
        migratedDb.execSQL(referenceCreateSql)
        val expected = readColumns(migratedDb, referenceTable)
        val actual = readColumns(migratedDb, tableName)
        assertEquals("`$tableName` disagrees with the generated v63 schema for $tableName", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    private fun openV62ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(62) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Copied verbatim from app/schemas/.../62.json's own createSql for each table -
                    // same "hand-builds each vNN-shaped table" reasoning [Migration61To62Test]'s own
                    // class doc gives.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`lineageId` INTEGER NOT NULL, `aspect` TEXT NOT NULL, `statement` TEXT NOT NULL, " +
                            "`targetValue` REAL, `unit` TEXT, `metricKey` TEXT, `deadlineEpoch` INTEGER, " +
                            "`status` TEXT NOT NULL, `supersedesId` INTEGER, `closedAt` INTEGER, " +
                            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                            "`syncId` TEXT NOT NULL DEFAULT '')",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_lineageId` ON `goals` (`lineageId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_goals_aspect_status` ON `goals` (`aspect`, `status`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `grocery_staples` (`name` TEXT NOT NULL, " +
                            "`displayName` TEXT NOT NULL, `timesBought` INTEGER NOT NULL, `lastBoughtAt` INTEGER NOT NULL, " +
                            "`syncId` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`name`))",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `item_lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, `tickable` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                            "`lastUsedAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL DEFAULT 0, `syncId` TEXT NOT NULL DEFAULT '', " +
                            "`deleted` INTEGER NOT NULL DEFAULT 0)",
                    )

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `list_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`listId` INTEGER NOT NULL, `text` TEXT NOT NULL, `done` INTEGER NOT NULL, `doneAt` INTEGER, " +
                            "`sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                            "`syncId` TEXT NOT NULL DEFAULT '', `deleted` INTEGER NOT NULL DEFAULT 0, " +
                            "`startsAt` INTEGER, `endsAt` INTEGER, `allDay` INTEGER NOT NULL, `triggerPlaceLabel` TEXT, " +
                            "`repeatKind` TEXT, `repeatEvery` INTEGER, `repeatDaysOfWeek` TEXT, `repeatDay` INTEGER, " +
                            "`repeatMonth` INTEGER, `repeatEndKind` TEXT, `repeatEndDate` INTEGER, `repeatEndCount` INTEGER, " +
                            "`exact` INTEGER NOT NULL DEFAULT 0, `exactDowngraded` INTEGER NOT NULL DEFAULT 0, " +
                            "`missedAt` INTEGER, `missedDismissedAt` INTEGER, `loggedAt` INTEGER DEFAULT NULL)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_items_listId` ON `list_items` (`listId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_list_items_startsAt` ON `list_items` (`startsAt`)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `every migrated table matches the generated v63 schema exactly`() {
        val db = openV62ShapedDatabase("migration_62_63_schema_test.db")
        MIGRATION_62_63.migrate(db)

        assertTableMatchesGeneratedSchema(db, "goals")
        assertTableMatchesGeneratedSchema(db, "grocery_staples")
        assertTableMatchesGeneratedSchema(db, "item_lists")
        assertTableMatchesGeneratedSchema(db, "list_items")
    }

    @Test
    fun `every existing row is preserved across all four tables`() {
        val db = openV62ShapedDatabase("migration_62_63_rows_test.db")
        db.execSQL("INSERT INTO goals (id, lineageId, aspect, statement, status, createdAt, syncId) VALUES (1, 100, 'bio', 'get to 175', 'active', 1000, 'goal-sync-1')")
        db.execSQL("INSERT INTO grocery_staples (name, displayName, timesBought, lastBoughtAt, syncId) VALUES ('milk', 'Milk', 3, 5000, 'staple-sync-1')")
        db.execSQL("INSERT INTO item_lists (id, name, tickable, sortOrder, lastUsedAt, archived, createdAt, syncId) VALUES (1, 'Car', 1, 0, 2000, 0, 1000, 'list-sync-1')")
        db.execSQL("INSERT INTO list_items (id, listId, text, done, sortOrder, createdAt, allDay, syncId) VALUES (1, 1, 'oil change', 0, 0, 1500, 1, 'item-sync-1')")

        MIGRATION_62_63.migrate(db)

        db.query("SELECT statement, syncId, serverId, deleted FROM goals WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("get to 175", c.getString(0))
            assertEquals("goal-sync-1", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals(0, c.getInt(3))
        }
        db.query("SELECT displayName, syncId, serverId, updatedAtMs, deleted FROM grocery_staples WHERE name = 'milk'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Milk", c.getString(0))
            assertEquals("staple-sync-1", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals(5000L, c.getLong(3))
            assertEquals(0, c.getInt(4))
        }
        db.query("SELECT name, syncId, serverId FROM item_lists WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Car", c.getString(0))
            assertEquals("list-sync-1", c.getString(1))
            assertTrue(c.isNull(2))
        }
        db.query("SELECT text, syncId, serverId FROM list_items WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("oil change", c.getString(0))
            assertEquals("item-sync-1", c.getString(1))
            assertTrue(c.isNull(2))
        }
    }

    @Test
    fun `a pre-existing duplicate syncId is deduplicated before the unique index is created`() {
        val db = openV62ShapedDatabase("migration_62_63_dedup_test.db")
        db.execSQL("INSERT INTO goals (id, lineageId, aspect, statement, status, createdAt, syncId) VALUES (1, 100, 'bio', 'a', 'active', 1000, 'dupe')")
        db.execSQL("INSERT INTO goals (id, lineageId, aspect, statement, status, createdAt, syncId) VALUES (2, 200, 'bio', 'b', 'active', 2000, 'dupe')")
        db.execSQL("INSERT INTO goals (id, lineageId, aspect, statement, status, createdAt, syncId) VALUES (3, 300, 'bio', 'c', 'active', 3000, '')")

        // Must not throw - this is the crash MIGRATION_61_62's own equivalent test guards against.
        MIGRATION_62_63.migrate(db)

        val syncIds = mutableListOf<String>()
        db.query("SELECT id, syncId FROM goals ORDER BY id").use { c ->
            while (c.moveToNext()) syncIds.add(c.getString(1))
        }
        assertEquals(3, syncIds.size)
        assertEquals(3, syncIds.distinct().size)
        assertTrue("no row should keep a blank syncId", syncIds.all { it.isNotBlank() })
        assertEquals("dupe", syncIds[0])
        assertNotEquals("dupe", syncIds[1])
    }

    @Test
    fun `the new unique syncId index on each table rejects a duplicate`() {
        val db = openV62ShapedDatabase("migration_62_63_index_test.db")
        MIGRATION_62_63.migrate(db)

        db.execSQL("INSERT INTO goals (lineageId, aspect, statement, status, createdAt, syncId, updatedAt, deleted) VALUES (1, 'bio', 'a', 'active', 1000, 'shared', 1000, 0)")
        var threwGoals = false
        try {
            db.execSQL("INSERT INTO goals (lineageId, aspect, statement, status, createdAt, syncId, updatedAt, deleted) VALUES (2, 'bio', 'b', 'active', 2000, 'shared', 2000, 0)")
        } catch (e: Exception) {
            threwGoals = true
        }
        assertTrue("duplicate goals.syncId must be rejected", threwGoals)

        db.execSQL("INSERT INTO grocery_staples (name, displayName, timesBought, lastBoughtAt, syncId, updatedAtMs, deleted) VALUES ('a', 'A', 1, 1000, 'shared', 1000, 0)")
        var threwStaples = false
        try {
            db.execSQL("INSERT INTO grocery_staples (name, displayName, timesBought, lastBoughtAt, syncId, updatedAtMs, deleted) VALUES ('b', 'B', 1, 2000, 'shared', 2000, 0)")
        } catch (e: Exception) {
            threwStaples = true
        }
        assertTrue("duplicate grocery_staples.syncId must be rejected", threwStaples)

        db.execSQL("INSERT INTO item_lists (name, tickable, sortOrder, lastUsedAt, archived, createdAt, updatedAt, syncId, deleted) VALUES ('a', 1, 0, 1000, 0, 1000, 1000, 'shared', 0)")
        var threwLists = false
        try {
            db.execSQL("INSERT INTO item_lists (name, tickable, sortOrder, lastUsedAt, archived, createdAt, updatedAt, syncId, deleted) VALUES ('b', 1, 0, 2000, 0, 2000, 2000, 'shared', 0)")
        } catch (e: Exception) {
            threwLists = true
        }
        assertTrue("duplicate item_lists.syncId must be rejected", threwLists)

        db.execSQL("INSERT INTO list_items (listId, text, done, sortOrder, createdAt, allDay, updatedAt, syncId, deleted) VALUES (1, 'a', 0, 0, 1000, 1, 1000, 'shared', 0)")
        var threwItems = false
        try {
            db.execSQL("INSERT INTO list_items (listId, text, done, sortOrder, createdAt, allDay, updatedAt, syncId, deleted) VALUES (1, 'b', 0, 0, 2000, 1, 2000, 'shared', 0)")
        } catch (e: Exception) {
            threwItems = true
        }
        assertTrue("duplicate list_items.syncId must be rejected", threwItems)
    }
}
