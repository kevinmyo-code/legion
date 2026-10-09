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
 * [MIGRATION_74_75] - `events.pinnedByJson` (Kevin, 2026-10-09: household members pin suggestions).
 * Starts from the GENERATED v74 `events` table and compares the migrated table against the
 * GENERATED v75 one, column for column - the discipline [Migration73To74Test] follows: read the
 * JSON at run time, never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration74To75Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String)

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private fun createSql(version: Int, table: String): String {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
        )
        val file = checkNotNull(candidates.firstOrNull { it.exists() }) {
            "Could not find the generated $version.json. Run compileDebugKotlin first."
        }
        val schema = Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
        return checkNotNull(schema.database.entities.firstOrNull { it.tableName == table }) {
            "No $table in v$version"
        }.createSql
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

    /** A v74 `events` table, built from the generated v74 schema. */
    private fun openV74(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(74) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        db.execSQL(createSql(74, "events").replace("\${TABLE_NAME}", "events"))
        return db
    }

    private fun <T> withV74(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val db = openV74(name)
        try {
            return block(db)
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    @Test
    fun `events matches the generated v75 schema after the migration`() = withV74("m74-75-shape.db") { db ->
        MIGRATION_74_75.migrate(db)
        db.execSQL(createSql(75, "events").replace("\${TABLE_NAME}", "events_reference"))
        assertEquals(columns(db, "events_reference"), columns(db, "events"))
    }

    @Test
    fun `existing events keep every value and gain no pins`() = withV74("m74-75-rows.db") { db ->
        db.execSQL(
            "INSERT INTO `events` (`serverId`, `title`, `startsAt`, `allDay`, `source`, `done`, `exact`, " +
                "`exactDowngraded`, `updatedAtMs`, `kind`) " +
                "VALUES ('s1', 'Jazz night', 1000, 0, 'engine', 0, 0, 0, 2000, 'suggestion')",
        )
        MIGRATION_74_75.migrate(db)

        db.query("SELECT `title`, `startsAt`, `kind`, `pinnedByJson` FROM `events`").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Jazz night", c.getString(0))
            assertEquals(1000L, c.getLong(1))
            assertEquals("suggestion", c.getString(2))
            assertTrue("an event from before pins has none on file, never a guessed empty list", c.isNull(3))
            assertTrue("exactly one row", !c.moveToNext())
        }
    }
}
