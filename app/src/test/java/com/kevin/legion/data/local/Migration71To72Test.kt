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
 * [MIGRATION_71_72] - `checklists.systemKey` (Kevin, 2026-10-05: Groceries is a built-in list).
 * Starts from the GENERATED v71 `checklists` table and compares the migrated table against the
 * GENERATED v72 one, column for column - the discipline [Migration70To71Test] established: read
 * the JSON at run time, never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration71To72Test {
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

    /** A v71 `checklists` table, built from the generated v71 schema. */
    private fun openV71(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(71) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        db.execSQL(createSql(71, "checklists").replace("\${TABLE_NAME}", "checklists"))
        return db
    }

    private fun <T> withV71(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val db = openV71(name)
        try {
            return block(db)
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    @Test
    fun `checklists matches the generated v72 schema after the migration`() = withV71("m71-72-shape.db") { db ->
        MIGRATION_71_72.migrate(db)
        db.execSQL(createSql(72, "checklists").replace("\${TABLE_NAME}", "checklists_reference"))
        assertEquals(columns(db, "checklists_reference"), columns(db, "checklists"))
    }

    @Test
    fun `existing lists keep every value and gain a null system key`() = withV71("m71-72-rows.db") { db ->
        db.execSQL(
            "INSERT INTO `checklists` (`name`, `recursDaily`, `sortOrder`, `createdAt`, `archived`, `updatedAt`, " +
                "`syncId`, `deleted`) VALUES ('Groceries', 1, 0, 1, 0, 1, 'sync-1', 0)",
        )
        MIGRATION_71_72.migrate(db)

        db.query("SELECT `name`, `syncId`, `systemKey` FROM `checklists`").use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Groceries", c.getString(0))
            assertEquals("sync-1", c.getString(1))
            assertTrue("no list on the phone was built in before the engine said so", c.isNull(2))
            assertTrue("exactly one row", !c.moveToNext())
        }
    }
}
