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
 * [MIGRATION_77_78] - `book_bookmarks.syncUuid` and `book_sync_outbox` (books ticket 09). Starts from
 * a v77-shaped database, runs the migration, and compares the migrated tables against the GENERATED v78 schema,
 * column for column - the discipline [Migration75To76Test] follows: read the JSON at run time,
 * never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration77To78Test {
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

    private fun <T> withV77(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(77) {
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

    private fun v77Books(db: SupportSQLiteDatabase) {
        MIGRATION_75_76.migrate(db)
        MIGRATION_76_77.migrate(db)
    }

    @Test
    fun `book_bookmarks and book_sync_outbox match the generated v78 schema after the migration`() =
        withV77("m77-78-shape.db") { db ->
            v77Books(db)
            MIGRATION_77_78.migrate(db)
            val bookmarks = entity(78, "book_bookmarks").createSql
            db.execSQL(bookmarks.replace("\${TABLE_NAME}", "book_bookmarks_reference"))
            assertEquals(columns(db, "book_bookmarks_reference"), columns(db, "book_bookmarks"))
            val outbox = entity(78, "book_sync_outbox").createSql
            db.execSQL(outbox.replace("\${TABLE_NAME}", "book_sync_outbox_reference"))
            assertEquals(columns(db, "book_sync_outbox_reference"), columns(db, "book_sync_outbox"))
            assertTrue(columns(db, "book_bookmarks").any { it.name == "syncUuid" && it.notNull == 0 })
        }

    @Test
    fun `an existing bookmark survives with no sync id and the index is kept`() = withV77("m77-78-keep.db") { db ->
        v77Books(db)
        db.execSQL(
            "INSERT INTO book_bookmarks (documentId, label, locatorJson, pageIndex, progress, createdAtMs) " +
                "VALUES ('doc', 'Page 3', NULL, 2, 0.1, 5)",
        )
        MIGRATION_77_78.migrate(db)
        db.query("SELECT label, syncUuid FROM book_bookmarks").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Page 3", c.getString(0))
            assertTrue(c.isNull(1))
        }
        assertTrue(indexNames(db, "book_bookmarks").contains("index_book_bookmarks_documentId"))
    }

    @Test
    fun `the migration is idempotent and the outbox starts empty`() = withV77("m77-78-empty.db") { db ->
        v77Books(db)
        MIGRATION_77_78.migrate(db)
        MIGRATION_77_78.migrate(db)
        db.query("SELECT COUNT(*) FROM `book_sync_outbox`").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }

    @Test
    fun `the migration leaves the conversions table alone`() = withV77("m77-78-conv.db") { db ->
        v77Books(db)
        val before = columns(db, "book_conversions")
        MIGRATION_77_78.migrate(db)
        assertEquals(before, columns(db, "book_conversions"))
    }
}
