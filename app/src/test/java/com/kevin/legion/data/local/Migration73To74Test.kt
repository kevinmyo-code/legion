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
 * [MIGRATION_73_74] - `places.address` (Kevin, 2026-10-09: save a place by its address). Starts
 * from the GENERATED v73 `places` table and compares the migrated table against the GENERATED v74
 * one, column for column - the discipline [Migration70To71Test] established: read the JSON at run
 * time, never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration73To74Test {
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

    /** A v73 `places` table, built from the generated v73 schema. */
    private fun openV73(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(73) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        db.execSQL(createSql(73, "places").replace("\${TABLE_NAME}", "places"))
        return db
    }

    private fun <T> withV73(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val db = openV73(name)
        try {
            return block(db)
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    @Test
    fun `places matches the generated v74 schema after the migration`() = withV73("m73-74-shape.db") { db ->
        MIGRATION_73_74.migrate(db)
        db.execSQL(createSql(74, "places").replace("\${TABLE_NAME}", "places_reference"))
        assertEquals(columns(db, "places_reference"), columns(db, "places"))
    }

    @Test
    fun `existing places keep every value and gain no address`() = withV73("m73-74-rows.db") { db ->
        db.execSQL(
            "INSERT INTO `places` (`label`, `latitude`, `longitude`, `timestamp`, `deleted`) " +
                "VALUES ('home', 29.7604, -95.3698, 1000, 0)",
        )
        MIGRATION_73_74.migrate(db)

        db.query("SELECT `label`, `latitude`, `timestamp`, `address` FROM `places`").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("home", c.getString(0))
            assertEquals(29.7604, c.getDouble(1), 0.0)
            assertEquals(1000L, c.getLong(2))
            assertTrue("a place saved before addresses has none on file, never a guessed one", c.isNull(3))
            assertTrue("exactly one row", !c.moveToNext())
        }
    }
}
