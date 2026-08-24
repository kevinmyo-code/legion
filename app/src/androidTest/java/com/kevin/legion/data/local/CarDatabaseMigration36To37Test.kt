package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v36 -> v37 - `records.guid`, the cross-device identity column
 * (senior review of aspect-engine ticket 20, MUST-FIX 1; see [EngineRecord]'s and
 * [MIGRATION_36_37]'s own doc comments for the defect this fixes and the exact three-step shape).
 * `createSql`/index text confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/37.json` after a real `compileDebugKotlin
 * -Pnokey` run, byte for byte (`guid` TEXT NOT NULL DEFAULT '' plus a unique index) - not
 * hand-derived, same discipline every migration test above this one documents.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence (a real upgrade backfilling real
 * pre-existing `records` rows with distinct guids) is deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration36To37Test {
    private val dbName = "migration-test-36-37"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun seedRecordAtV36(db: androidx.sqlite.db.SupportSQLiteDatabase, recordTypeId: Long, searchText: String) {
        db.execSQL(
            "INSERT INTO records (recordTypeId, createdAt, updatedAt, dueAt, amountCents, searchText, provenance, payload, deletedAt) " +
                "VALUES ($recordTypeId, 1000, 1000, NULL, NULL, '$searchText', 'USER', '{}', NULL)"
        )
    }

    @Test
    fun `migrate36To37_isPurelyAdditive_existingRowsUntouchedExceptGuid`() {
        helper.createDatabase(dbName, 36).apply {
            seedRecordAtV36(this, recordTypeId = 1, searchText = "one")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 37, true, MIGRATION_36_37)

        val cursor = db.query("SELECT recordTypeId, searchText, provenance FROM records WHERE searchText = 'one'")
        assertTrue(cursor.moveToFirst())
        assertEquals(1, cursor.getInt(0))
        assertEquals("one", cursor.getString(1))
        assertEquals("USER", cursor.getString(2))
        cursor.close()
    }

    @Test
    fun `migrate36To37_backfillsEveryPreExistingRowWithARealDistinctGuid_neverBlank`() {
        helper.createDatabase(dbName, 36).apply {
            seedRecordAtV36(this, recordTypeId = 1, searchText = "row-a")
            seedRecordAtV36(this, recordTypeId = 1, searchText = "row-b")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 37, true, MIGRATION_36_37)

        val guids = mutableListOf<String>()
        val cursor = db.query("SELECT guid FROM records ORDER BY id")
        while (cursor.moveToNext()) guids += cursor.getString(0)
        cursor.close()

        assertEquals(2, guids.size)
        for (g in guids) assertFalse("no row may be left with the placeholder blank guid", g.isBlank())
        assertNotEquals("two pre-existing rows must never backfill to the SAME guid", guids[0], guids[1])
    }

    @Test
    fun `migrate36To37_guidIsUnique_aDuplicateInsertFails`() {
        helper.createDatabase(dbName, 36).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 37, true, MIGRATION_36_37)

        db.execSQL(
            "INSERT INTO records (recordTypeId, createdAt, updatedAt, searchText, provenance, payload, guid) " +
                "VALUES (1, 1000, 1000, 'first', 'USER', '{}', 'same-guid')"
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO records (recordTypeId, createdAt, updatedAt, searchText, provenance, payload, guid) " +
                    "VALUES (1, 1000, 1000, 'second', 'USER', '{}', 'same-guid')"
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("guid must be UNIQUE - a duplicate insert must fail, not silently duplicate", threw)
    }

    @Test
    fun `migrate36To37_freshInsertWithNoGuidColumnGetsTheEmptyStringDefault_neverCrashes`() {
        helper.createDatabase(dbName, 36).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 37, true, MIGRATION_36_37)

        // A caller that inserts without naming `guid` at all (the SQL-level default, distinct from
        // the Kotlin-level UUID.randomUUID() default RecordStore.create actually uses) must not
        // crash - it lands on the SQL DEFAULT ''.
        db.execSQL(
            "INSERT INTO records (recordTypeId, createdAt, updatedAt, searchText, provenance, payload) " +
                "VALUES (1, 1000, 1000, 'no-guid-named', 'USER', '{}')"
        )
        val cursor = db.query("SELECT guid FROM records WHERE searchText = 'no-guid-named'")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        cursor.close()
    }
}
