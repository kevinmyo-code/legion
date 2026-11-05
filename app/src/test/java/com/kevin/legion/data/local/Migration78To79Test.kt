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
 * [MIGRATION_78_79] - `vehicles_replica.plate` (memory-and-vault ticket 13). Builds the v78
 * `vehicles_replica` from the GENERATED 78.json, runs the migration, and compares the result with
 * the GENERATED 79.json column for column - read at run time, never hand-transcribed.
 */
@RunWith(RobolectricTestRunner::class)
class Migration78To79Test {
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

    private fun <T> withV78(name: String, block: (SupportSQLiteDatabase) -> T): T {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(78) {
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

    /** `vehicles_replica` exactly as v78 generated it, with its index. */
    private fun v78Replica(db: SupportSQLiteDatabase) {
        val v78 = entity(78, "vehicles_replica")
        db.execSQL(v78.createSql.replace("\${TABLE_NAME}", "vehicles_replica"))
        for (index in v78.indices) db.execSQL(index.createSql.replace("\${TABLE_NAME}", "vehicles_replica"))
    }

    @Test
    fun `vehicles_replica matches the generated v79 schema after the migration`() = withV78("m78-79-shape.db") { db ->
        v78Replica(db)
        MIGRATION_78_79.migrate(db)
        val reference = entity(79, "vehicles_replica").createSql
        db.execSQL(reference.replace("\${TABLE_NAME}", "vehicles_replica_reference"))
        assertEquals(columns(db, "vehicles_replica_reference"), columns(db, "vehicles_replica"))
        assertTrue(columns(db, "vehicles_replica").any { it.name == "plate" && it.notNull == 0 && it.dflt == null })
    }

    @Test
    fun `an existing car survives with no plate and the index is kept`() = withV78("m78-79-keep.db") { db ->
        v78Replica(db)
        db.execSQL(
            "INSERT INTO vehicles_replica " +
                "(serverId, name, make, model, year, confirmed, updatedAtMs, deleted, archived) " +
                "VALUES ('v1', 'the wagon', 'Subaru', 'Outback', 2016, 1, 5, 0, 0)",
        )
        MIGRATION_78_79.migrate(db)
        db.query("SELECT name, plate FROM vehicles_replica").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("the wagon", c.getString(0))
            assertTrue(c.isNull(1))
        }
        assertTrue(indexNames(db, "vehicles_replica").contains("index_vehicles_replica_serverId"))
    }

    @Test
    fun `the migration is idempotent`() = withV78("m78-79-twice.db") { db ->
        v78Replica(db)
        MIGRATION_78_79.migrate(db)
        MIGRATION_78_79.migrate(db)
        assertEquals(1, columns(db, "vehicles_replica").count { it.name == "plate" })
    }
}
