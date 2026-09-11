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
 * [MIGRATION_69_70] - one new table, `feed_subscriptions` (one-home ticket 07, the RSS feed URLs
 * Kevin typed). Purely additive: nothing existed before this migration to preserve, so unlike
 * [Migration68To69Test] there is no "row survives" case to prove - the whole test is that the
 * table this migration creates matches the LIVE generated v70 schema byte for byte, same
 * "read the generated JSON at run time rather than hand-transcribe it" discipline that test and
 * [Migration67To68Test] both established.
 */
@RunWith(RobolectricTestRunner::class)
class Migration69To70Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaIndex(val createSql: String)

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String, val indices: List<SchemaIndex> = emptyList())

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

    private fun entity(version: Int, tableName: String): SchemaEntity {
        val entity = schema(version).database.entities.firstOrNull { it.tableName == tableName }
        return checkNotNull(entity) { "No entity named $tableName in the generated v$version schema." }
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

    /** A v69-shaped database with no `feed_subscriptions` table at all - the starting point every
     * install upgrading from v69 has. */
    private fun openV69ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(69) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `feed_subscriptions is created and matches the generated v70 schema`() {
        val db = openV69ShapedDatabase("migration-69-70-shape.db")
        try {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'feed_subscriptions'").use { c ->
                assertTrue("v69 must not already have feed_subscriptions, or this test proves nothing", !c.moveToNext())
            }

            MIGRATION_69_70.migrate(db)

            val generated = entity(70, "feed_subscriptions")
            db.execSQL(generated.createSql.replace("\${TABLE_NAME}", "feed_subscriptions_generated_reference"))
            for (index in generated.indices) {
                db.execSQL(
                    index.createSql
                        .replace("\${TABLE_NAME}", "feed_subscriptions_generated_reference")
                        .replace(
                            "index_feed_subscriptions_url",
                            "index_feed_subscriptions_generated_reference_url",
                        ),
                )
            }

            val expected = readColumns(db, "feed_subscriptions_generated_reference")
            val actual = readColumns(db, "feed_subscriptions")
            assertEquals("`feed_subscriptions` disagrees with the generated v70 schema", expected, actual)

            db.query("PRAGMA index_list(`feed_subscriptions`)").use { c ->
                assertTrue("feed_subscriptions must have its unique url index", c.moveToNext())
                assertEquals(1, c.getInt(c.getColumnIndexOrThrow("unique")))
            }

            db.execSQL("DROP TABLE `feed_subscriptions_generated_reference`")
        } finally {
            db.close()
            context.getDatabasePath("migration-69-70-shape.db").delete()
        }
    }

    @Test
    fun `a row can be inserted with no title, since title is nullable`() {
        val db = openV69ShapedDatabase("migration-69-70-insert.db")
        try {
            MIGRATION_69_70.migrate(db)
            db.execSQL(
                "INSERT INTO `feed_subscriptions` (`url`, `title`, `addedAtMs`) VALUES ('https://example.com/feed.xml', NULL, 1000)",
            )
            db.query("SELECT `url`, `title`, `addedAtMs` FROM `feed_subscriptions`").use { c ->
                assertTrue(c.moveToNext())
                assertEquals("https://example.com/feed.xml", c.getString(0))
                assertTrue(c.isNull(1))
                assertEquals(1000L, c.getLong(2))
            }
        } finally {
            db.close()
            context.getDatabasePath("migration-69-70-insert.db").delete()
        }
    }

    @Test
    fun `a duplicate url is rejected by the unique index`() {
        val db = openV69ShapedDatabase("migration-69-70-unique.db")
        try {
            MIGRATION_69_70.migrate(db)
            db.execSQL(
                "INSERT INTO `feed_subscriptions` (`url`, `title`, `addedAtMs`) VALUES ('https://example.com/feed.xml', NULL, 1000)",
            )
            var threw = false
            try {
                db.execSQL(
                    "INSERT INTO `feed_subscriptions` (`url`, `title`, `addedAtMs`) VALUES ('https://example.com/feed.xml', 'again', 2000)",
                )
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                threw = true
            }
            assertTrue("the unique index on url must reject a duplicate", threw)
        } finally {
            db.close()
            context.getDatabasePath("migration-69-70-unique.db").delete()
        }
    }
}
