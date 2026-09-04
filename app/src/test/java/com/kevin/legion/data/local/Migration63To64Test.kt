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
 * [MIGRATION_63_64] - recurring checklists' schema half (`checklists`, `checklist_items`,
 * `checklist_ticks`), all three brand-new tables with nothing existing altered.
 *
 * Same "read the LIVE generated `64.json` at run time" discipline [Migration62To63Test] established
 * - see that class's own doc comment for why a hand-transcribed comparison is not good enough.
 */
@RunWith(RobolectricTestRunner::class)
class Migration63To64Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String)

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private val schemaJson: SchemaFile by lazy {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/64.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/64.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/64.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so kapt emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v64 schema."
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
        assertEquals("`$tableName` disagrees with the generated v64 schema for $tableName", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    /** v63 had none of these three tables at all - the "before" shape is simply an empty v63
     * database, unlike [Migration62To63Test]'s rebuild-of-existing-tables shape. */
    private fun openV63ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(63) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Nothing - checklists/checklist_items/checklist_ticks did not exist in v63.
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `every migrated table matches the generated v64 schema exactly`() {
        val db = openV63ShapedDatabase("migration_63_64_schema_test.db")
        MIGRATION_63_64.migrate(db)

        assertTableMatchesGeneratedSchema(db, "checklists")
        assertTableMatchesGeneratedSchema(db, "checklist_items")
        assertTableMatchesGeneratedSchema(db, "checklist_ticks")
    }

    @Test
    fun `checklists can be inserted after migration`() {
        val db = openV63ShapedDatabase("migration_63_64_insert_test.db")
        MIGRATION_63_64.migrate(db)

        db.execSQL(
            "INSERT INTO checklists (name, recursDaily, sortOrder, createdAt, archived, updatedAt, syncId, deleted) " +
                "VALUES ('bio', 1, 0, 1000, 0, 1000, 'checklist-sync-1', 0)",
        )
        db.query("SELECT name FROM checklists WHERE name = 'bio'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("bio", c.getString(0))
        }
    }

    @Test
    fun `the unique index on checklist_ticks itemId,day rejects a duplicate`() {
        val db = openV63ShapedDatabase("migration_63_64_index_test.db")
        MIGRATION_63_64.migrate(db)

        db.execSQL(
            "INSERT INTO checklist_ticks (itemId, day, tickedAt, updatedAt, syncId, deleted) " +
                "VALUES (1, 20000, 1000, 1000, 'tick-sync-1', 0)",
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO checklist_ticks (itemId, day, tickedAt, updatedAt, syncId, deleted) " +
                    "VALUES (1, 20000, 2000, 2000, 'tick-sync-2', 0)",
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("duplicate (itemId, day) must be rejected", threw)
    }
}
