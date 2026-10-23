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
 * [MIGRATION_76_77] - `book_conversions` (books ticket 05). Starts from a database that lacks the
 * table, runs the migration, and compares the migrated table against the GENERATED v77 schema,
 * column for column - the discipline [Migration75To76Test] follows: read the JSON at run time,
 * never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration76To77Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaIndex(val name: String, val createSql: String)

    @Serializable
    private data class SchemaEntity(
        val tableName: String,
        val createSql: String,
        val indices: List<SchemaIndex> = emptyList(),
    )

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private fun entity(version: Int, table: String): SchemaEntity {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
        )
        val file = checkNotNull(candidates.firstOrNull { it.exists() }) {
            "Could not find the generated $version.json. Run compileDebugKotlin first."
        }
        val schema = Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
        return checkNotNull(schema.database.entities.firstOrNull { it.tableName == table }) { "No $table in v$version" }
    }

    private data class Column(val name: String, val type: String, val notNull: Int, val dflt: String?, val pk: Int)

    private fun columns(db: SupportSQLiteDatabase, table: String): List<Column> {
        val out = mutableListOf<Column>()
        db.query("PRAGMA table_info(`$table`)").use { c ->
            while (c.moveToNext()) {
                val d = c.getColumnIndexOrThrow("dflt_value")
                out += Column(
                    c.getString(c.getColumnIndexOrThrow("name")),
                    c.getString(c.getColumnIndexOrThrow("type")),
                    c.getInt(c.getColumnIndexOrThrow("notnull")),
                    if (c.isNull(d)) null else c.getString(d),
                    c.getInt(c.getColumnIndexOrThrow("pk")),
                )
            }
        }
        return out.sortedBy { it.name }
    }

    private fun indexNames(db: SupportSQLiteDatabase, table: String): List<String> {
        val out = mutableListOf<String>()
        db.query("PRAGMA index_list(`$table`)").use { c ->
            while (c.moveToNext()) out += c.getString(c.getColumnIndexOrThrow("name"))
        }
        return out.sorted()
    }

    private fun <T> withV76(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(76) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        try {
            return block(db)
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    @Test
    fun `book_conversions matches the generated v77 schema after the migration`() =
        withV76("m76-77-shape.db") { db ->
            MIGRATION_76_77.migrate(db)
            val create = entity(77, "book_conversions").createSql
            db.execSQL(create.replace("\${TABLE_NAME}", "book_conversions_reference"))
            assertEquals(columns(db, "book_conversions_reference"), columns(db, "book_conversions"))
            assertEquals(
                listOf("attempts", "message", "pages", "sha256", "status", "updatedAt"),
                columns(db, "book_conversions").map { it.name },
            )
        }

    @Test
    fun `the migration is idempotent and the new table starts empty`() = withV76("m76-77-empty.db") { db ->
        MIGRATION_76_77.migrate(db)
        MIGRATION_76_77.migrate(db)
        db.query("SELECT COUNT(*) FROM `book_conversions`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `the migration leaves the books tables alone`() = withV76("m76-77-books.db") { db ->
        MIGRATION_75_76.migrate(db)
        val before = columns(db, "books")
        MIGRATION_76_77.migrate(db)
        assertEquals(before, columns(db, "books"))
        assertTrue(indexNames(db, "book_bookmarks").contains("index_book_bookmarks_documentId"))
    }
}
