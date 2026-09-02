package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_61_62] - the ledger-config-supabase ticket's schema half (`categories`,
 * `category_rules`, `budget_targets`).
 *
 * **This test reads the LIVE generated `app/schemas/.../62.json` at run time and asserts the
 * migration's own output matches it column-for-column, rather than trusting a hand-copied SQL
 * string.** CLAUDE.md's own live-sync brief calls out that every prior migration test in this
 * package (going back to [Migration60To61Test]) validated a migration only against ITS OWN
 * output - a hand-transcription of the generated `createSql` into the migration and into the test
 * could drift from the real schema and neither would ever notice. [assertTableMatchesGeneratedSchema]
 * closes that gap: it re-derives what Room ACTUALLY generated for the current entity definitions
 * (by creating a throwaway reference table from the same `createSql` this test reads out of the
 * schema JSON, then comparing `PRAGMA table_info` between it and the migrated table) rather than
 * comparing two copies of a string one person typed twice. If [MIGRATION_61_62] and
 * `CarDatabase.kt`'s entity definitions ever disagree - a column renamed on one side only, a
 * default dropped, a type changed - this test fails on the mismatch instead of passing because
 * both sides happened to be transcribed identically by hand.
 */
@RunWith(RobolectricTestRunner::class)
class Migration61To62Test {
    private val context = RuntimeEnvironment.getApplication()

    // --- Reading the generated schema JSON, not a hand-copied string ----------------------------

    @Serializable
    private data class SchemaIndex(val name: String, val createSql: String)

    @Serializable
    private data class SchemaEntity(val tableName: String, val createSql: String, val indices: List<SchemaIndex> = emptyList())

    @Serializable
    private data class SchemaDatabaseBody(val entities: List<SchemaEntity>)

    @Serializable
    private data class SchemaFile(val database: SchemaDatabaseBody)

    private val schemaJson: SchemaFile by lazy {
        // Gradle's unit-test working directory is the module root (`app/`) for this project's
        // setup, but both are tried so this does not silently pass-by-accident on a different
        // invocation shape - a missing file fails loudly via the check() below, never silently
        // skips the comparison.
        val candidates = listOf(
            File("schemas/com.kevin.legion.data.local.CarDatabase/62.json"),
            File("app/schemas/com.kevin.legion.data.local.CarDatabase/62.json"),
        )
        val file = candidates.firstOrNull { it.exists() }
        checkNotNull(file) {
            "Could not find the generated schemas/com.kevin.legion.data.local.CarDatabase/62.json " +
                "from any of: ${candidates.map { it.absolutePath }}. Run compileDebugKotlin first " +
                "so kapt emits it."
        }
        Json { ignoreUnknownKeys = true }.decodeFromString(SchemaFile.serializer(), file.readText())
    }

    private fun generatedEntity(tableName: String): SchemaEntity =
        checkNotNull(schemaJson.database.entities.firstOrNull { it.tableName == tableName }) {
            "No entity named $tableName in the generated v62 schema."
        }

    /** Column facts read off `PRAGMA table_info` - name, declared type, NOT NULL, default value,
     * and primary-key position - everything a `createSql` mismatch could change. */
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

    /**
     * The real schema-diff assertion: builds a THROWAWAY reference table from the CURRENT
     * generated `createSql` for [tableName] (read fresh out of `62.json` every run), then asserts
     * [migratedDb]'s own post-[MIGRATION_61_62] table has the exact same columns. A migration that
     * silently drifted from the entity definitions - wrong type, missing column, wrong default -
     * fails here even though [MIGRATION_61_62] only ever compares against its own output otherwise.
     */
    private fun assertTableMatchesGeneratedSchema(migratedDb: SupportSQLiteDatabase, tableName: String) {
        val entity = generatedEntity(tableName)
        val referenceTable = "${tableName}_generated_reference"
        val referenceCreateSql = entity.createSql.replace("\${TABLE_NAME}", referenceTable)
        migratedDb.execSQL(referenceCreateSql)
        val expected = readColumns(migratedDb, referenceTable)
        val actual = readColumns(migratedDb, tableName)
        assertEquals("`$tableName` disagrees with the generated v62 schema for $tableName", expected, actual)
        migratedDb.execSQL("DROP TABLE `$referenceTable`")
    }

    private fun openV61ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(61) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Copied verbatim from app/schemas/.../61.json's own createSql for each table -
                    // same "hand-builds each v61-shaped table" reasoning [Migration60To61Test]'s own
                    // class doc gives: the live schema already carries every v62 column, so
                    // `ALTER TABLE ... ADD COLUMN` against it would fail on a duplicate column.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`name` TEXT NOT NULL, `isFoodCategory` INTEGER NOT NULL)",
                    )
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_name` ON `categories` (`name`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `category_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`category` TEXT NOT NULL, `substring` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `budget_targets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`category` TEXT NOT NULL, `currency` TEXT NOT NULL, `amountCents` INTEGER NOT NULL, " +
                            "`effectiveFromMonthEpoch` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_budget_targets_category_currency_effectiveFromMonthEpoch` " +
                            "ON `budget_targets` (`category`, `currency`, `effectiveFromMonthEpoch`)",
                    )
                    // The three memory tables, v61-shaped: MIGRATION_60_61 already added
                    // syncId/serverId/updatedAtMs/deleted (guid too, for memory_audit) but created
                    // no index on them - see [MIGRATION_61_62]'s own class doc, "also folds in the
                    // fix for a regression..." Copied straight from 61.json's own createSql with
                    // the literal table name already in place, same as the three tables above.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`text` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `syncId` TEXT NOT NULL DEFAULT '', " +
                            "`serverId` TEXT, `updatedAtMs` INTEGER NOT NULL DEFAULT 0, `deleted` INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `companion_memories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`vehicleId` TEXT NOT NULL, `text` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                            "`importance` INTEGER NOT NULL DEFAULT 5, `createdAt` INTEGER NOT NULL, " +
                            "`lastAccessedAt` INTEGER NOT NULL DEFAULT 0, `embeddingVector` TEXT, `embeddingModel` TEXT, " +
                            "`syncId` TEXT NOT NULL DEFAULT '', `serverId` TEXT, `updatedAtMs` INTEGER NOT NULL DEFAULT 0, " +
                            "`deleted` INTEGER NOT NULL DEFAULT 0)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `memory_audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`event` TEXT NOT NULL, `store` TEXT NOT NULL, `detail` TEXT NOT NULL, " +
                            "`refId` INTEGER NOT NULL DEFAULT 0, `vehicleId` TEXT NOT NULL DEFAULT '', `at` INTEGER NOT NULL, " +
                            "`guid` TEXT NOT NULL DEFAULT '', `serverId` TEXT, `updatedAtMs` INTEGER NOT NULL DEFAULT 0, " +
                            "`deleted` INTEGER NOT NULL DEFAULT 0)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `every migrated table matches the generated v62 schema exactly`() {
        val db = openV61ShapedDatabase("migration_61_62_schema_test.db")
        MIGRATION_61_62.migrate(db)

        assertTableMatchesGeneratedSchema(db, "categories")
        assertTableMatchesGeneratedSchema(db, "category_rules")
        assertTableMatchesGeneratedSchema(db, "budget_targets")
        assertTableMatchesGeneratedSchema(db, "memories")
        assertTableMatchesGeneratedSchema(db, "companion_memories")
        assertTableMatchesGeneratedSchema(db, "memory_audit")
    }

    @Test
    fun `preserves every existing row and mints a distinct guid on all three tables`() {
        val db = openV61ShapedDatabase("migration_61_62_backfill_test.db")
        db.execSQL("INSERT INTO categories (id, name, isFoodCategory) VALUES (1, 'Groceries', 1)")
        db.execSQL("INSERT INTO categories (id, name, isFoodCategory) VALUES (2, 'Pets', 0)")
        db.execSQL("INSERT INTO category_rules (id, category, substring, createdAt) VALUES (1, 'Groceries', 'KROGER', 5000)")
        db.execSQL(
            "INSERT INTO budget_targets (id, category, currency, amountCents, effectiveFromMonthEpoch, updatedAt) " +
                "VALUES (1, 'Groceries', 'USD', 40000, 1000000, 9000)",
        )

        MIGRATION_61_62.migrate(db)

        // categories: rows preserved, guid minted and distinct.
        val categoryGuids = mutableListOf<String>()
        db.query("SELECT id, name, isFoodCategory, guid, serverId, updatedAtMs, deleted FROM categories ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Groceries", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertTrue(cursor.getString(3).isNotBlank())
            assertTrue(cursor.isNull(4))
            assertTrue(cursor.getLong(5) > 0)
            assertEquals(0, cursor.getInt(6))
            categoryGuids.add(cursor.getString(3))
            assertTrue(cursor.moveToNext())
            assertEquals("Pets", cursor.getString(1))
            categoryGuids.add(cursor.getString(3))
        }
        assertEquals(2, categoryGuids.distinct().size)

        // category_rules: row preserved, updatedAtMs backfilled from createdAt.
        db.query("SELECT category, substring, createdAt, guid, updatedAtMs, deleted FROM category_rules WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Groceries", cursor.getString(0))
            assertEquals("KROGER", cursor.getString(1))
            assertEquals(5000L, cursor.getLong(2))
            assertTrue(cursor.getString(3).isNotBlank())
            assertEquals(5000L, cursor.getLong(4))
            assertEquals(0, cursor.getInt(5))
        }

        // budget_targets: row preserved, updatedAt UNCHANGED (it is the sync clock already, not
        // backfilled from anything else), no separate updatedAtMs column.
        db.query("SELECT category, currency, amountCents, effectiveFromMonthEpoch, updatedAt, guid, serverId, deleted FROM budget_targets WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Groceries", cursor.getString(0))
            assertEquals("USD", cursor.getString(1))
            assertEquals(40000L, cursor.getLong(2))
            assertEquals(1000000L, cursor.getLong(3))
            assertEquals(9000L, cursor.getLong(4))
            assertTrue(cursor.getString(5).isNotBlank())
            assertTrue(cursor.isNull(6))
            assertEquals(0, cursor.getInt(7))
        }
    }

    @Test
    fun `two pre-existing rows on the same table get two different minted guids`() {
        val db = openV61ShapedDatabase("migration_61_62_uniqueness_test.db")
        db.execSQL("INSERT INTO categories (id, name, isFoodCategory) VALUES (1, 'a', 0)")
        db.execSQL("INSERT INTO categories (id, name, isFoodCategory) VALUES (2, 'b', 0)")

        MIGRATION_61_62.migrate(db)

        val guids = mutableListOf<String>()
        db.query("SELECT guid FROM categories ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) guids.add(cursor.getString(0))
        }
        assertEquals(2, guids.size)
        assertNotEquals(guids[0], guids[1])
    }

    @Test
    fun `the new unique guid index on each table rejects a duplicate`() {
        val db = openV61ShapedDatabase("migration_61_62_index_test.db")
        MIGRATION_61_62.migrate(db)

        db.execSQL("INSERT INTO categories (name, isFoodCategory, guid, updatedAtMs, deleted) VALUES ('a', 0, 'guid-a', 1000, 0)")
        var threw = false
        try {
            db.execSQL("INSERT INTO categories (name, isFoodCategory, guid, updatedAtMs, deleted) VALUES ('b', 0, 'guid-a', 2000, 0)")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("duplicate guid on categories must be rejected by the new unique index", threw)
    }

    @Test
    fun `re-creates the unique index MIGRATION_60_61 lost, and rejects a duplicate afterward`() {
        // The regression this ticket fixes: MemoryEntry/CompanionMemory/MemoryAudit declared no
        // @Entity(indices = ...) when MIGRATION_60_61 was rewritten as a table rebuild, so 61.json
        // carried no index and the rebuild created none - Migration60To61Test's own index test used
        // to assert this and now correctly no longer can (moved here). This asserts MIGRATION_61_62
        // puts it back.
        val db = openV61ShapedDatabase("migration_61_62_memory_index_test.db")
        MIGRATION_61_62.migrate(db)

        db.execSQL("INSERT INTO memories (text, timestamp, syncId, updatedAtMs, deleted) VALUES ('a', 1000, 'shared', 1000, 0)")
        var threwMemories = false
        try {
            db.execSQL("INSERT INTO memories (text, timestamp, syncId, updatedAtMs, deleted) VALUES ('b', 2000, 'shared', 2000, 0)")
        } catch (e: Exception) {
            threwMemories = true
        }
        assertTrue("duplicate memories.syncId must be rejected by the recreated unique index", threwMemories)

        db.execSQL(
            "INSERT INTO companion_memories (vehicleId, text, category, source, createdAt, syncId, updatedAtMs, deleted) " +
                "VALUES ('jeep', 'a', 'driver', 'consolidated', 1000, 'shared', 1000, 0)",
        )
        var threwCompanion = false
        try {
            db.execSQL(
                "INSERT INTO companion_memories (vehicleId, text, category, source, createdAt, syncId, updatedAtMs, deleted) " +
                    "VALUES ('jeep', 'b', 'driver', 'consolidated', 2000, 'shared', 2000, 0)",
            )
        } catch (e: Exception) {
            threwCompanion = true
        }
        assertTrue("duplicate companion_memories.syncId must be rejected by the recreated unique index", threwCompanion)

        db.execSQL("INSERT INTO memory_audit (event, store, detail, at, guid, updatedAtMs, deleted) VALUES ('written', 'memories', 'a', 1000, 'shared', 1000, 0)")
        var threwAudit = false
        try {
            db.execSQL("INSERT INTO memory_audit (event, store, detail, at, guid, updatedAtMs, deleted) VALUES ('written', 'memories', 'b', 2000, 'shared', 2000, 0)")
        } catch (e: Exception) {
            threwAudit = true
        }
        assertTrue("duplicate memory_audit.guid must be rejected by the recreated unique index", threwAudit)
    }

    @Test
    fun `a pre-existing duplicate syncId or guid is deduplicated before the index is created`() {
        // The dedup pass this migration MUST run before CREATE UNIQUE INDEX, or the migration
        // itself would crash on launch on any phone that already has two rows sharing a value -
        // the same crash shape MIGRATION_60_61's own ALTER-based first draft hit, for an unrelated
        // reason. Two rows share 'dupe', one row has a genuinely blank syncId; both cases must come
        // out unique and non-blank on the other side, and neither may collide with the other.
        val db = openV61ShapedDatabase("migration_61_62_memory_dedup_test.db")
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (1, 'a', 1000, 'dupe')")
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (2, 'b', 2000, 'dupe')")
        db.execSQL("INSERT INTO memories (id, text, timestamp, syncId) VALUES (3, 'c', 3000, '')")

        // Must not throw - this is the crash this test guards against.
        MIGRATION_61_62.migrate(db)

        val syncIds = mutableListOf<String>()
        db.query("SELECT id, syncId FROM memories ORDER BY id").use { cursor ->
            while (cursor.moveToNext()) syncIds.add(cursor.getString(1))
        }
        assertEquals(3, syncIds.size)
        assertEquals(3, syncIds.distinct().size)
        assertTrue("no row should keep a blank syncId", syncIds.all { it.isNotBlank() })
        // Row 1 (lowest id in the duplicate group) keeps its original value; row 2 is re-minted.
        assertEquals("dupe", syncIds[0])
        assertNotEquals("dupe", syncIds[1])
    }

    @Test
    fun `every table gains guid serverId and deleted, and categories plus category_rules also gain updatedAtMs`() {
        val db = openV61ShapedDatabase("migration_61_62_all_tables_test.db")
        MIGRATION_61_62.migrate(db)

        fun columnsOf(table: String): Set<String> {
            val columns = mutableSetOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
            return columns
        }

        for (table in listOf("categories", "category_rules", "budget_targets")) {
            val columns = columnsOf(table)
            assertTrue("$table missing guid", "guid" in columns)
            assertTrue("$table missing serverId", "serverId" in columns)
            assertTrue("$table missing deleted", "deleted" in columns)
        }
        assertTrue("categories missing updatedAtMs", "updatedAtMs" in columnsOf("categories"))
        assertTrue("category_rules missing updatedAtMs", "updatedAtMs" in columnsOf("category_rules"))
        // budget_targets deliberately does NOT get a separate updatedAtMs - its existing
        // `updatedAt` column is the sync clock, matching MealTarget's own v59/v60 precedent.
        assertTrue("budget_targets should not have a separate updatedAtMs", "updatedAtMs" !in columnsOf("budget_targets"))
    }
}
