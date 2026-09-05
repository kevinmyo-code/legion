package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_65_66] - checklists get measured items (`checklist_items`/`checklist_ticks`) and real
 * schedules (`checklists`), one-today ticket 09's second build.
 *
 * Same "read the LIVE generated `66.json` at run time" discipline [Migration62To63Test] established
 * - see that class's own doc comment for why a hand-transcribed comparison is not good enough.
 */
@RunWith(RobolectricTestRunner::class)
class Migration65To66Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String)

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private val schemaJson: SchemaFile by lazy {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/66.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/66.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/66.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so kapt emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v66 schema."
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
        assertEquals("`$tableName` disagrees with the generated v66 schema for $tableName", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    /** v65-shaped `checklists`/`checklist_items`/`checklist_ticks` - copied verbatim from
     * `app/schemas/.../65.json`'s own createSql for each table, same "hand-builds each vNN-shaped
     * table" reasoning [Migration62To63Test]'s own class doc gives. */
    private fun openV65ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(65) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `checklists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, `recursDaily` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `archived` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL DEFAULT 0, `syncId` TEXT NOT NULL DEFAULT '', " +
                            "`serverId` TEXT, `deleted` INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_checklists_syncId` ON `checklists` (`syncId`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `checklist_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`checklistId` INTEGER NOT NULL, `text` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, " +
                            "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL DEFAULT 0, " +
                            "`syncId` TEXT NOT NULL DEFAULT '', `serverId` TEXT, `deleted` INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_checklist_items_checklistId` ON `checklist_items` (`checklistId`)")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_checklist_items_syncId` ON `checklist_items` (`syncId`)")

                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `checklist_ticks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`itemId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `tickedAt` INTEGER NOT NULL, " +
                            "`updatedAt` INTEGER NOT NULL DEFAULT 0, `syncId` TEXT NOT NULL DEFAULT '', " +
                            "`serverId` TEXT, `deleted` INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_checklist_ticks_itemId_day` ON `checklist_ticks` (`itemId`, `day`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_checklist_ticks_day` ON `checklist_ticks` (`day`)")
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `every migrated table matches the generated v66 schema exactly`() {
        val db = openV65ShapedDatabase("migration_65_66_schema_test.db")
        MIGRATION_65_66.migrate(db)

        assertTableMatchesGeneratedSchema(db, "checklists")
        assertTableMatchesGeneratedSchema(db, "checklist_items")
        assertTableMatchesGeneratedSchema(db, "checklist_ticks")
    }

    @Test
    fun `every existing tick backfills to USER_REPORTED with a null value`() {
        val db = openV65ShapedDatabase("migration_65_66_ticks_test.db")
        db.execSQL(
            "INSERT INTO checklist_ticks (id, itemId, day, tickedAt, updatedAt, syncId, deleted) " +
                "VALUES (1, 1, 20000, 1000, 1000, 'tick-sync-1', 0)",
        )

        MIGRATION_65_66.migrate(db)

        db.query("SELECT value, source FROM checklist_ticks WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("a pre-existing tick never had a measured value", c.isNull(0))
            assertEquals("USER_REPORTED", c.getString(1))
        }
    }

    @Test
    fun `a pre-existing recursDaily checklist is backfilled to the DAILY schedule`() {
        val db = openV65ShapedDatabase("migration_65_66_daily_test.db")
        db.execSQL(
            "INSERT INTO checklists (id, name, recursDaily, sortOrder, createdAt, archived, updatedAt, syncId, deleted) " +
                "VALUES (1, 'bio', 1, 0, 1000, 0, 1000, 'checklist-sync-1', 0)",
        )
        db.execSQL(
            "INSERT INTO checklists (id, name, recursDaily, sortOrder, createdAt, archived, updatedAt, syncId, deleted) " +
                "VALUES (2, 'one-off packing list', 0, 0, 1000, 0, 1000, 'checklist-sync-2', 0)",
        )

        MIGRATION_65_66.migrate(db)

        db.query("SELECT scheduleKind, scheduleEvery FROM checklists WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("DAILY", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        db.query("SELECT scheduleKind, scheduleEvery FROM checklists WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("a non-recurring checklist gets no schedule, not DAILY", c.isNull(0))
            assertTrue(c.isNull(1))
        }
    }

    @Test
    fun `a measured item can be inserted with its measure columns after migration`() {
        val db = openV65ShapedDatabase("migration_65_66_measure_test.db")
        MIGRATION_65_66.migrate(db)

        db.execSQL(
            "INSERT INTO checklist_items (id, checklistId, text, sortOrder, createdAt, updatedAt, syncId, deleted, " +
                "measureUnit, measureTarget, measureDirection) " +
                "VALUES (1, 1, 'walk 10k steps', 0, 1000, 1000, 'item-sync-1', 0, 'steps', 10000.0, 'AT_LEAST')",
        )
        db.query("SELECT measureUnit, measureTarget, measureDirection FROM checklist_items WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("steps", c.getString(0))
            assertEquals(10000.0, c.getDouble(1), 0.0)
            assertEquals("AT_LEAST", c.getString(2))
        }
    }
}
