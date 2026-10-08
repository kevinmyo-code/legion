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
 * [MIGRATION_72_73] - the `merchant_aliases` table (Kevin, 2026-10-07). Compares the migrated table
 * against the GENERATED v73 one, column for column, reading the JSON at run time rather than
 * hand-transcribing it (the discipline [Migration71To72Test] follows).
 */
@RunWith(RobolectricTestRunner::class)
class Migration72To73Test {
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

    private fun <T> withDb(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(72) {
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
    fun `merchant_aliases matches the generated v73 schema after the migration`() = withDb("m72-73-shape.db") { db ->
        MIGRATION_72_73.migrate(db)
        db.execSQL(createSql(73, "merchant_aliases").replace("\${TABLE_NAME}", "merchant_aliases_reference"))
        assertEquals(columns(db, "merchant_aliases_reference"), columns(db, "merchant_aliases"))
    }

    @Test
    fun `the guid index is unique`() = withDb("m72-73-index.db") { db ->
        MIGRATION_72_73.migrate(db)
        val insert = "INSERT INTO `merchant_aliases` (`substring`, `displayName`, `createdAt`, `guid`) VALUES "
        db.execSQL(insert + "('A', 'a', 1, 'g1')")
        val duplicate = runCatching { db.execSQL(insert + "('B', 'b', 2, 'g1')") }
        assertTrue("a second row with the same guid must be refused", duplicate.isFailure)
    }
}
