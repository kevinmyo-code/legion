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
 * [MIGRATION_64_65] - `voice_notes` gets `transcriptionFailureReason`/`transcriptionAttemptStartedAt`
 * (the failed/in-flight-transcription vocabulary, see [VoiceNote]'s own doc comment).
 *
 * Same "read the LIVE generated `65.json` at run time" discipline [Migration62To63Test] established
 * - see that class's own doc comment for why a hand-transcribed comparison is not good enough.
 */
@RunWith(RobolectricTestRunner::class)
class Migration64To65Test {
    private val context = RuntimeEnvironment.getApplication()

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String)

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private val schemaJson: SchemaFile by lazy {
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/65.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/65.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/65.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so kapt emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v65 schema."
        }

    private data class ColumnInfo(val name: String, val type: String, val notNull: Int, val dfltValue: String?, val pk: Int)

    private fun readColumns(db: SupportSQLiteDatabase, table: String): List<ColumnInfo> {
        val columns = mutableListOf<ColumnInfo>()
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            while (cursor.moveToNext()) {
                columns.add(
                    ColumnInfo(
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        type = cursor.getString(cursor.getColumnIndexOrThrow("type")),
                        notNull = cursor.getInt(cursor.getColumnIndexOrThrow("notnull")),
                        dfltValue = if (cursor.isNull(cursor.getColumnIndexOrThrow("dflt_value"))) null else cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")),
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
        assertEquals("`$tableName` disagrees with the generated v65 schema for $tableName", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    private fun openV64ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(64) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Copied verbatim from app/schemas/.../64.json's own createSql for voice_notes -
                    // same "hand-builds each vNN-shaped table" reasoning [Migration62To63Test]'s own
                    // class doc gives.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `voice_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`serverId` TEXT, `startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `title` TEXT, " +
                            "`summary` TEXT, `transcript` TEXT, `audioPath` TEXT, `kind` TEXT NOT NULL, " +
                            "`provenance` TEXT NOT NULL, `interrupted` INTEGER NOT NULL)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `the migrated voice_notes table matches the generated v65 schema exactly`() {
        val db = openV64ShapedDatabase("migration_64_65_schema_test.db")
        MIGRATION_64_65.migrate(db)

        assertTableMatchesGeneratedSchema(db, "voice_notes")
    }

    @Test
    fun `every existing row is preserved, with both new columns defaulting to null`() {
        val db = openV64ShapedDatabase("migration_64_65_rows_test.db")
        db.execSQL(
            "INSERT INTO voice_notes (id, startedAt, endedAt, title, kind, provenance, interrupted) " +
                "VALUES (1, 1000, 2000, 'Standup', 'SOLO', 'LLM_DERIVED', 0)",
        )

        MIGRATION_64_65.migrate(db)

        db.query(
            "SELECT title, transcriptionFailureReason, transcriptionAttemptStartedAt FROM voice_notes WHERE id = 1",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Standup", c.getString(0))
            assertTrue("a pre-existing row must never gain a failure it never had", c.isNull(1))
            assertTrue("a pre-existing row must never gain an in-flight marker it never had", c.isNull(2))
        }
    }
}
