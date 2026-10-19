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
 * [MIGRATION_75_76] - `books` and `book_bookmarks` (the Books launcher, ADR 0061). Starts from a
 * database that has neither table, runs the migration, and compares each migrated table and the
 * bookmarks index against the GENERATED v76 schema, column for column - the discipline
 * [Migration74To75Test] follows: read the JSON at run time, never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration75To76Test {
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

    private fun <T> withV75(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(75) {
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
    fun `books and book_bookmarks match the generated v76 schema after the migration`() =
        withV75("m75-76-shape.db") { db ->
            MIGRATION_75_76.migrate(db)
            for (table in listOf("books", "book_bookmarks")) {
                db.execSQL(entity(76, table).createSql.replace("\${TABLE_NAME}", "${table}_reference"))
                assertEquals(table, columns(db, "${table}_reference"), columns(db, table))
            }
        }

    @Test
    fun `the bookmarks index is created exactly as the generated schema declares it`() =
        withV75("m75-76-index.db") { db ->
            MIGRATION_75_76.migrate(db)
            val declared = entity(76, "book_bookmarks").indices.map { it.name }.sorted()
            assertEquals(declared, indexNames(db, "book_bookmarks"))
            assertTrue(declared.contains("index_book_bookmarks_documentId"))
        }

    @Test
    fun `the migration is idempotent and the new tables start empty`() = withV75("m75-76-empty.db") { db ->
        MIGRATION_75_76.migrate(db)
        MIGRATION_75_76.migrate(db)
        for (table in listOf("books", "book_bookmarks")) {
            db.query("SELECT COUNT(*) FROM `$table`").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0, c.getInt(0))
            }
        }
    }

    @Test
    fun `a saved position survives an insert-if-absent rescan`() = withV75("m75-76-rows.db") { db ->
        MIGRATION_75_76.migrate(db)
        db.execSQL(
            "INSERT INTO `books` (`documentId`, `fileName`, `format`, `title`, `metadataRead`, `progress`, " +
                "`pageIndex`, `pageCount`, `lastReadAtMs`, `firstSeenAtMs`) " +
                "VALUES ('d', 'a.pdf', 'PDF', 'a', 0, 0.5, 20, 40, 99, 1)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `books` (`documentId`, `fileName`, `format`, `title`, `metadataRead`, " +
                "`progress`, `pageIndex`, `pageCount`, `firstSeenAtMs`) VALUES ('d', 'a.pdf', 'PDF', 'a', 0, 0, 0, 0, 2)",
        )
        db.query("SELECT `progress`, `pageIndex`, `lastReadAtMs` FROM `books`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0.5, c.getDouble(0), 1e-9)
            assertEquals(20, c.getInt(1))
            assertEquals(99L, c.getLong(2))
            assertTrue("exactly one row", !c.moveToNext())
        }
    }
}
