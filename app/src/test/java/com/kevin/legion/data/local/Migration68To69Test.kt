package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_68_69] - one nullable column, `checklists.sourceKey`, added by one-home ticket 05 so a
 * machine writer can find the checklist it owns by a real key instead of by scanning display text
 * for `"Plan: "` (ticket 04's resolution).
 *
 * Same "read the LIVE generated JSON at run time" discipline [Migration67To68Test] and
 * [Migration62To63Test] established, and this test goes one step further: **the v68 starting table
 * is built from the generated `68.json` rather than hand-transcribed.** A hand-written v68
 * `CREATE TABLE` would agree with a hand-written migration for exactly as long as both were wrong in
 * the same way, and `checklists` is a wide table where a transcription slip is easy and silent.
 *
 * The additive claim is the one that matters here. CLAUDE.md §5 allows `ALTER TABLE ... ADD COLUMN`
 * and forbids a rebuild, so this asserts both halves: the new column is present and matches v69, and
 * **every row that existed before the migration is still there afterwards with its values intact.**
 * A rebuild that silently dropped rows would satisfy a column check on its own.
 */
@RunWith(RobolectricTestRunner::class)
class Migration68To69Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String)

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private fun schema(version: Int): SchemaFile {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated $version.json from any of " +
                "${candidates.map { it.absolutePath }}. Run compileDebugKotlin first so the " +
                "annotation processor emits it."
        }
        return Json { ignoreUnknownKeys = true }
            .decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun createSql(version: Int, tableName: String, asTable: String): String {
        val entity = schema(version).database.entities.firstOrNull { it.tableName == tableName }
        checkNotNull(entity) { "No entity named $tableName in the generated v$version schema." }
        return entity.createSql.replace("\${TABLE_NAME}", asTable)
    }

    private data class ColumnInfo(
        val name: String,
        val type: String,
        val notNull: Int,
        val dfltValue: String?,
        val pk: Int,
    )

    private fun readColumns(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(
                    ColumnInfo(
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        dfltValue = if (cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) {
                            null
                        } else {
                            cursor.getString(cursor.getColumnIndexOrThrow("dflt_value"))
                        },
                        pk = cursor.getInt(cursor.getColumnIndexOrThrow("pk")),
                    ),
                )
            }
        }
        return columns.sortedBy { it.name }
    }

    /** A v68-shaped `checklists`, built from the generated v68 schema and seeded with a row, so
     * "the migration ran against real content" is something this test can claim rather than assume. */
    private fun openV68ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(68) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(createSql(68, "checklists", "checklists"))
                    // Every NOT NULL column with no default has to be named, or the INSERT fails
                    // on a constraint rather than on anything this test is about. Read off the
                    // generated v68 schema: id, name, recursDaily, sortOrder, createdAt, archived.
                    db.execSQL(
                        "INSERT INTO `checklists` " +
                            "(`id`, `name`, `recursDaily`, `sortOrder`, `createdAt`, `archived`) " +
                            "VALUES (1, 'A list made by hand', 0, 0, 1000, 0)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `sourceKey is added and the table matches the generated v69 schema`() {
        val db = openV68ShapedDatabase("migration-68-69-shape.db")
        try {
            assertTrue(
                "the v68 table must not already have sourceKey, or this test proves nothing",
                readColumns(db, "checklists").none { it.name == "sourceKey" },
            )

            MIGRATION_68_69.migrate(db)

            // Compare against a reference table built from the LIVE generated v69 schema rather
            // than against a hand-written expectation.
            db.execSQL(createSql(69, "checklists", "checklists_generated_reference"))
            val expected = readColumns(db, "checklists_generated_reference")
            val actual = readColumns(db, "checklists")
            assertEquals("`checklists` disagrees with the generated v69 schema", expected, actual)
            db.execSQL("DROP TABLE `checklists_generated_reference`")
        } finally {
            db.close()
            context.getDatabasePath("migration-68-69-shape.db").delete()
        }
    }

    @Test
    fun `the migration is additive - the row that was already there survives untouched`() {
        val db = openV68ShapedDatabase("migration-68-69-additive.db")
        try {
            MIGRATION_68_69.migrate(db)

            db.query("SELECT `id`, `name`, `sourceKey` FROM `checklists`").use { c ->
                assertTrue("the pre-existing row is gone - this migration rebuilt the table", c.moveToNext())
                assertEquals(1L, c.getLong(0))
                assertEquals("A list made by hand", c.getString(1))
                // A checklist a person made carries no source key. Null is the correct answer, and
                // the column must not have been backfilled with anything - a real key identifies
                // OWNERSHIP by a machine writer, and inventing one for a hand-made list would make
                // the advisor adopt it on its next run.
                assertTrue("a hand-made checklist must have a null sourceKey", c.isNull(2))
                assertTrue("the migration added a row from nowhere", !c.moveToNext())
            }
        } finally {
            db.close()
            context.getDatabasePath("migration-68-69-additive.db").delete()
        }
    }

    @Test
    fun `sourceKey is nullable, so an existing install is not forced to invent one`() {
        val db = openV68ShapedDatabase("migration-68-69-nullable.db")
        try {
            MIGRATION_68_69.migrate(db)
            val col = readColumns(db, "checklists").first { it.name == "sourceKey" }
            assertEquals("sourceKey must be TEXT", "TEXT", col.type)
            assertEquals("sourceKey must be nullable", 0, col.notNull)
            // An INSERT that names no sourceKey must still work - this is what makes the column
            // additive in practice rather than only in the DDL.
            db.execSQL(
                "INSERT INTO `checklists` " +
                    "(`id`, `name`, `recursDaily`, `sortOrder`, `createdAt`, `archived`) " +
                    "VALUES (2, 'another hand-made list', 0, 0, 2000, 0)",
            )
            db.query("SELECT `sourceKey` FROM `checklists` WHERE `id` = 2").use { c ->
                assertTrue(c.moveToNext())
                assertNull(if (c.isNull(0)) null else c.getString(0))
            }
        } finally {
            db.close()
            context.getDatabasePath("migration-68-69-nullable.db").delete()
        }
    }
}
