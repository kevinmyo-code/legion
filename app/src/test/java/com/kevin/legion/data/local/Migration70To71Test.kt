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
 * [MIGRATION_70_71] - `categories.excludedFromSpend` plus the starter `Transfers` row (Kevin,
 * 2026-09-29: "ignore zelle for spending"). Starts from the GENERATED v70 `categories` table and
 * compares the migrated table against the GENERATED v71 one, column for column, the discipline
 * [Migration69To70Test] established: read the JSON at run time, never hand-transcribe it.
 */
@RunWith(RobolectricTestRunner::class)
class Migration70To71Test {
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

    /** A v70 `categories` table, built from the generated v70 schema, with its unique indices. */
    private fun openV70(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(70) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        db.execSQL(createSql(70, "categories").replace("\${TABLE_NAME}", "categories"))
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_guid` ON `categories` (`guid`)")
        return db
    }

    private fun <T> withV70(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val db = openV70(name)
        try {
            return block(db)
        } finally {
            db.close()
            context.getDatabasePath(name).delete()
        }
    }

    @Test
    fun `categories matches the generated v71 schema after the migration`() = withV70("m70-71-shape.db") { db ->
        MIGRATION_70_71.migrate(db)
        db.execSQL(createSql(71, "categories").replace("\${TABLE_NAME}", "categories_reference"))
        assertEquals(columns(db, "categories_reference"), columns(db, "categories"))
    }

    @Test
    fun `existing categories stay spending and Transfers is seeded not-spending`() = withV70("m70-71-seed.db") { db ->
        db.execSQL("INSERT INTO `categories` (`name`, `isFoodCategory`, `guid`) VALUES ('Groceries', 1, 'g-1')")
        MIGRATION_70_71.migrate(db)

        val sql = "SELECT `name`, `excludedFromSpend`, `guid`, `serverId`, `deleted` FROM `categories` ORDER BY `name`"
        db.query(sql).use { c ->
            assertTrue(c.moveToNext())
            assertEquals("Groceries", c.getString(0))
            assertEquals(0, c.getInt(1))
            assertTrue(c.moveToNext())
            assertEquals("Transfers", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertTrue("Transfers needs its own guid for the backfill to push it", c.getString(2).length == 36)
            assertTrue("a seeded row has never been on the server", c.isNull(3))
            assertEquals(0, c.getInt(4))
        }
    }

    @Test
    fun `an existing Transfers keeps its row and gains the flag`() = withV70("m70-71-existing.db") { db ->
        db.execSQL(
            "INSERT INTO `categories` (`name`, `isFoodCategory`, `guid`, `serverId`) " +
                "VALUES ('Transfers', 0, 'mine', 'srv-1')",
        )
        MIGRATION_70_71.migrate(db)

        val sql = "SELECT `guid`, `serverId`, `excludedFromSpend` FROM `categories` WHERE `name` = 'Transfers'"
        db.query(sql).use { c ->
            assertTrue(c.moveToNext())
            assertEquals("mine", c.getString(0))
            assertEquals("srv-1", c.getString(1))
            assertEquals(1, c.getInt(2))
            assertTrue("exactly one Transfers row", !c.moveToNext())
        }
    }
}
