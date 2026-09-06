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
 * [MIGRATION_67_68] - three new tables and nothing else: [GeminiUsage] and [LiveConnectDay], which
 * make Gemini spend measurable for the first time, and [BackgroundPassState], which stops the two
 * unattended five-minute loops paying repeatedly for the same no-op.
 *
 * Same "read the LIVE generated `68.json` at run time" discipline [Migration62To63Test]
 * established - see that class's own doc comment for why a hand-transcribed comparison is not good
 * enough. A hand-written expectation would agree with a hand-written migration for exactly as long
 * as both were wrong in the same way.
 */
@RunWith(RobolectricTestRunner::class)
class Migration67To68Test {
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
            File("schemas/com.kevin.legion.data.local.CarDatabase/68.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/68.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/68.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so the annotation processor emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v68 schema."
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
        assertEquals("`$tableName` disagrees with the generated v68 schema", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    /**
     * A v67-shaped database. Only `conversation_audit` is materialised, because
     * [MIGRATION_67_68] reads no existing table at all - it is three `CREATE TABLE`s - and a table
     * the migration never touches proves nothing by being present. Its real job here is to give
     * the helper a v67 database that is not empty, so "the migration ran against real content" is
     * a claim the test can make rather than assume.
     */
    private fun openV67ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(67) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `conversation_audit` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `turnSeq` INTEGER NOT NULL, " +
                            "`kind` TEXT NOT NULL, `toolName` TEXT NOT NULL DEFAULT '', " +
                            "`args` TEXT NOT NULL DEFAULT '', `content` TEXT NOT NULL, " +
                            "`redacted` INTEGER NOT NULL DEFAULT 0, `vehicleId` TEXT NOT NULL DEFAULT '', " +
                            "`at` INTEGER NOT NULL, `clientUuid` TEXT NOT NULL DEFAULT '')",
                    )
                    db.execSQL(
                        "INSERT INTO conversation_audit " +
                            "(turnSeq, kind, toolName, args, content, redacted, vehicleId, at, clientUuid) " +
                            "VALUES (1, 'companion', '', '', 'a pre-existing row', 0, '', 1000, 'uuid-1')",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Never reached: this helper only ever OPENS a v67-shaped database, and each
                    // test drives MIGRATION_67_68 by hand. Failing loudly beats an empty block that
                    // would silently swallow an unexpected upgrade path.
                    error("openV67ShapedDatabase must not be upgraded by the helper")
                }
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `every new table matches the generated v68 schema exactly`() {
        val db = openV67ShapedDatabase("migration_67_68_schema_test.db")
        MIGRATION_67_68.migrate(db)

        assertTableMatchesGeneratedSchema(db, "gemini_usage")
        assertTableMatchesGeneratedSchema(db, "live_connect_day")
        assertTableMatchesGeneratedSchema(db, "background_pass_state")
    }

    @Test
    fun `both gemini_usage indices match the ones Room generates, uniqueness included`() {
        val db = openV67ShapedDatabase("migration_67_68_index_test.db")
        MIGRATION_67_68.migrate(db)

        val generated = generatedEntity("gemini_usage").indices.associateBy { it.name }
        assertEquals(
            "the entity is expected to declare exactly the sessionKey and at indices",
            setOf("index_gemini_usage_sessionKey", "index_gemini_usage_at"),
            generated.keys,
        )

        val found = mutableMapOf<String, Int>()
        db.query("PRAGMA index_list(`gemini_usage`)").use { c ->
            while (c.moveToNext()) {
                found[c.getString(c.getColumnIndexOrThrow("name"))] =
                    c.getInt(c.getColumnIndexOrThrow("unique"))
            }
        }
        for ((name, index) in generated) {
            assertTrue("$name is missing after the migration", found.containsKey(name))
            assertEquals(
                "$name's uniqueness must match what Room declares",
                if (index.unique) 1 else 0,
                found[name],
            )
        }
    }

    @Test
    fun `the unique index on sessionKey actually rejects a duplicate`() {
        // The PRAGMA above says the index is declared unique; this says it BEHAVES unique, which is
        // the property GeminiUsageMeter's insert-then-fold relies on to stay exact when two
        // usageMetadata reports for one socket are metered concurrently.
        val db = openV67ShapedDatabase("migration_67_68_unique_behaviour_test.db")
        MIGRATION_67_68.migrate(db)

        db.execSQL(
            "INSERT INTO gemini_usage (sessionKey, surface, model, reports, at, updatedAt) " +
                "VALUES ('session-a', 'live', 'm', 0, 1, 1)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO gemini_usage (sessionKey, surface, model, reports, at, updatedAt) " +
                "VALUES ('session-a', 'live', 'm', 0, 2, 2)",
        )

        db.query("SELECT COUNT(*) FROM gemini_usage WHERE sessionKey = 'session-a'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("the second insert must be ignored, not stored", 1, c.getInt(0))
        }
    }

    @Test
    fun `the token columns are nullable, because unreported and zero are different facts`() {
        // The single most load-bearing schema property of gemini_usage: Gemini omits usageMetadata
        // on some 200 responses, and a NOT NULL column would force that unknown to be stored as 0,
        // which the Setup screen would then report as "0 tokens" - telling Kevin he is fine exactly
        // when the app cannot see. CLAUDE.md section 1, the ContentResolver rule.
        val db = openV67ShapedDatabase("migration_67_68_nullable_test.db")
        MIGRATION_67_68.migrate(db)

        db.execSQL(
            "INSERT INTO gemini_usage (sessionKey, surface, model, reports, at, updatedAt) " +
                "VALUES ('unreported', 'rest', 'm', 1, 5, 5)",
        )
        db.query(
            "SELECT promptTokens, responseTokens, totalTokens FROM gemini_usage WHERE sessionKey = 'unreported'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue("promptTokens must stay NULL, never default to 0", c.isNull(0))
            assertTrue("responseTokens must stay NULL, never default to 0", c.isNull(1))
            assertTrue("totalTokens must stay NULL, never default to 0", c.isNull(2))
        }
    }

    @Test
    fun `the pre-existing table is untouched, because the migration is purely additive`() {
        val db = openV67ShapedDatabase("migration_67_68_additive_test.db")
        MIGRATION_67_68.migrate(db)

        db.query("SELECT content FROM conversation_audit WHERE clientUuid = 'uuid-1'").use { c ->
            assertTrue("the pre-existing row must survive", c.moveToFirst())
            assertEquals("a pre-existing row", c.getString(0))
        }
    }
}
