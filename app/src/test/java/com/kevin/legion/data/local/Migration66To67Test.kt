package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_66_67] - `conversation_audit` gets [ConversationAudit.clientUuid] and the unique index
 * over it, the client-minted identity that replaces `(device_id, local_id)` as this table's server
 * key after that pair silently discarded three days of audit rows.
 *
 * Same "read the LIVE generated `67.json` at run time" discipline [Migration62To63Test] established
 * - see that class's own doc comment for why a hand-transcribed comparison is not good enough.
 */
@RunWith(RobolectricTestRunner::class)
class Migration66To67Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaIndex(val name: String, val unique: Boolean, val columnNames: List<String>)

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

    private val schemaJson: SchemaFile by lazy {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/67.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/67.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/67.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so the annotation processor emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v67 schema."
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

    private fun assertTableMatchesGeneratedSchema(migratedDb: SupportSQLiteDatabase, tableName: String) {
        val entity = generatedEntity(tableName)
        val referenceTable = "${tableName}_generated_reference"
        val referenceCreateSql = entity.createSql.replace("\${TABLE_NAME}", referenceTable)
        migratedDb.execSQL(referenceCreateSql)
        val expected = readColumns(migratedDb, referenceTable)
        val actual = readColumns(migratedDb, tableName)
        assertEquals("`$tableName` disagrees with the generated v67 schema", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    /** v66-shaped `conversation_audit` - copied verbatim from `app/schemas/.../66.json`'s own
     *  createSql, same "hand-builds each vNN-shaped table" reasoning [Migration62To63Test]'s own
     *  class doc gives. */
    private fun openV66ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(66) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `conversation_audit` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `turnSeq` INTEGER NOT NULL, " +
                            "`kind` TEXT NOT NULL, `toolName` TEXT NOT NULL DEFAULT '', " +
                            "`args` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL, " +
                            "`redacted` INTEGER NOT NULL DEFAULT 0, `vehicleId` TEXT NOT NULL DEFAULT '', " +
                            "`at` INTEGER NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Never reached: this helper only ever OPENS a v66-shaped database, and each
                    // test drives MIGRATION_66_67 by hand. Failing loudly beats an empty block that
                    // would silently swallow an unexpected upgrade path.
                    error("openV66ShapedDatabase must not be upgraded by the helper")
                }
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    private fun insertV66Row(db: SupportSQLiteDatabase, id: Long, content: String) {
        db.execSQL(
            "INSERT INTO conversation_audit (id, turnSeq, kind, toolName, args, content, redacted, vehicleId, at) " +
                "VALUES ($id, 1, 'companion', '', '', '$content', 0, '', 1000)",
        )
    }

    @Test
    fun `the migrated table matches the generated v67 schema exactly`() {
        val db = openV66ShapedDatabase("migration_66_67_schema_test.db")
        MIGRATION_66_67.migrate(db)

        assertTableMatchesGeneratedSchema(db, "conversation_audit")
    }

    @Test
    fun `the unique index matches the one Room generates`() {
        val db = openV66ShapedDatabase("migration_66_67_index_test.db")
        MIGRATION_66_67.migrate(db)

        val generated = generatedEntity("conversation_audit").indices.single()
        assertEquals("index_conversation_audit_clientUuid", generated.name)

        var found = false
        db.query("PRAGMA index_list(`conversation_audit`)").use { c ->
            while (c.moveToNext()) {
                if (c.getString(c.getColumnIndexOrThrow("name")) == generated.name) {
                    found = true
                    assertEquals(
                        "the migrated index must be unique, exactly as Room declares it",
                        1,
                        c.getInt(c.getColumnIndexOrThrow("unique")),
                    )
                }
            }
        }
        assertTrue("${generated.name} is missing after the migration", found)
    }

    @Test
    fun `every pre-existing row is backfilled with a distinct non-blank uuid`() {
        // The 142 rows on the real phone that the old key discarded: they must come out of this
        // migration with a real identity, because that identity is the only thing that lets them
        // finally upload.
        val db = openV66ShapedDatabase("migration_66_67_backfill_test.db")
        repeat(5) { insertV66Row(db, (it + 1).toLong(), "line $it") }

        MIGRATION_66_67.migrate(db)

        val uuids = mutableListOf<String>()
        db.query("SELECT clientUuid FROM conversation_audit ORDER BY id").use { c ->
            while (c.moveToNext()) uuids.add(c.getString(0))
        }
        assertEquals(5, uuids.size)
        uuids.forEach { assertFalse("a backfilled row must not be left blank", it.isBlank()) }
        assertEquals("every backfilled uuid must be distinct", 5, uuids.toSet().size)
    }

    @Test
    fun `a backfilled uuid is not the empty default the ADD COLUMN leaves behind`() {
        val db = openV66ShapedDatabase("migration_66_67_default_test.db")
        insertV66Row(db, 1L, "only")

        MIGRATION_66_67.migrate(db)

        db.query("SELECT clientUuid FROM conversation_audit WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertNotEquals("", c.getString(0))
        }
    }

    @Test
    fun `a row inserted after the migration can carry its own uuid`() {
        val db = openV66ShapedDatabase("migration_66_67_insert_test.db")
        MIGRATION_66_67.migrate(db)

        db.execSQL(
            "INSERT INTO conversation_audit " +
                "(id, turnSeq, kind, toolName, args, content, redacted, vehicleId, at, clientUuid) " +
                "VALUES (1, 1, 'user', '', '', 'hello', 0, '', 1000, 'uuid-from-the-phone')",
        )
        db.query("SELECT clientUuid FROM conversation_audit WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("uuid-from-the-phone", c.getString(0))
        }
    }

    @Test
    fun `the migration runs cleanly on an empty table`() {
        val db = openV66ShapedDatabase("migration_66_67_empty_test.db")
        MIGRATION_66_67.migrate(db)

        assertTableMatchesGeneratedSchema(db, "conversation_audit")
    }
}
