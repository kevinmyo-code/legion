package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_79_80] - the new `diag_scans` table (brand-neutral diagnostics, 2026-11-15). Runs the
 * migration on an empty v79 database and compares the table and its index with the GENERATED
 * 80.json, read at run time, never hand-transcribed.
 */
@RunWith(RobolectricTestRunner::class)
class Migration79To80Test {
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

    private fun schema(version: Int): SchemaDatabaseBody {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/$version.json"),
        )
        val file = checkNotNull(candidates.firstOrNull { it.exists() }) {
            "Could not find the generated $version.json. Run compileDebugKotlin first."
        }
        return Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText()).database
    }

    private fun entity(version: Int, table: String): SchemaEntity =
        checkNotNull(schema(version).entities.firstOrNull { it.tableName == table }) { "No $table in v$version" }

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

    private fun indexSql(db: SupportSQLiteDatabase, name: String): String? =
        db.query("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(name)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun <T> withV79(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(79) {
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
    fun `v79 had no diag_scans`() {
        assertFalse(schema(79).entities.any { it.tableName == "diag_scans" })
    }

    @Test
    fun `diag_scans matches the generated v80 schema after the migration`() = withV79("m79-80-shape.db") { db ->
        MIGRATION_79_80.migrate(db)
        val reference = entity(80, "diag_scans")
        db.execSQL(reference.createSql.replace("\${TABLE_NAME}", "diag_scans_reference"))
        assertEquals(columns(db, "diag_scans_reference"), columns(db, "diag_scans"))
        val generatedIndex = reference.indices.single()
        assertEquals("index_diag_scans_vehicleId_startedAtMs", generatedIndex.name)
        assertEquals(
            generatedIndex.createSql.replace("\${TABLE_NAME}", "diag_scans").replace("IF NOT EXISTS ", ""),
            indexSql(db, generatedIndex.name)?.replace("IF NOT EXISTS ", ""),
        )
    }

    @Test
    fun `the migration is idempotent and the table takes a row`() = withV79("m79-80-twice.db") { db ->
        MIGRATION_79_80.migrate(db)
        MIGRATION_79_80.migrate(db)
        db.execSQL(
            "INSERT INTO diag_scans (vehicleId, startedAtMs, finishedAtMs, bus, moduleCount, faultCount, complete, " +
                "reportJson) VALUES ('car:test', 1, 2, 'CAN_11', 3, 1, 1, '{}')",
        )
        db.query("SELECT COUNT(*) FROM diag_scans").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
    }
}
