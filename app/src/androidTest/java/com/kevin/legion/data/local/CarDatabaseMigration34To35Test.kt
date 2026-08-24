package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v34 -> v35 - the [WidgetInstance] grid-geometry columns
 * (`gridRow`/`gridCol`/`rowSpan`/`colSpan`, aspect-engine ticket 18). Four bare
 * `ALTER TABLE ... ADD COLUMN`s, nothing else touched. `createSql` confirmed against the
 * generated `app/schemas/com.kevin.legion.data.local.CarDatabase/35.json` after a real
 * `compileDebugKotlin -Pnokey` run (which also regenerated the schema WITH the `DEFAULT 0`/`1`
 * clauses once [WidgetInstance]'s four new fields gained `@ColumnInfo(defaultValue = ...)` -
 * senior review, 2026-08-23), same discipline [CarDatabaseMigration33To34Test]'s own doc states.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, same posture as
 * [CarDatabaseMigration33To34Test]. On-device evidence (a widget row inserted at v34 still reading
 * back after the upgrade, and the new columns defaulting to the entity's own 0/0/1/1) is deferred
 * to on-device QA. This closes the first gap in the migration-test sequence since v4 - every
 * version from 5 through 33 has no `CarDatabaseMigrationXToYTest` file of its own either, so this
 * (and 33To34 before it) are the first two, not a restored convention.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration34To35Test {
    private val dbName = "migration-test-34-35"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate34To35_isPurelyAdditive_existingRowUntouched`() {
        helper.createDatabase(dbName, 34).apply {
            execSQL(
                "INSERT INTO widget_instances (deviceId, aspectId, recordTypeId, widgetType, config, position, createdAt, updatedAt) " +
                    "VALUES ('device-1', NULL, NULL, 'STAT_TILE', '{}', 0, 1000, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 35, true, MIGRATION_34_35)

        val cursor = db.query("SELECT deviceId, widgetType FROM widget_instances WHERE deviceId = 'device-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("device-1", cursor.getString(0))
        assertEquals("STAT_TILE", cursor.getString(1))
        cursor.close()
    }

    @Test
    fun `migrate34To35_backfillsThePreExistingRowsNewColumnsToTheEntitysOwnDefaults`() {
        helper.createDatabase(dbName, 34).apply {
            execSQL(
                "INSERT INTO widget_instances (deviceId, aspectId, recordTypeId, widgetType, config, position, createdAt, updatedAt) " +
                    "VALUES ('device-1', NULL, NULL, 'STAT_TILE', '{}', 0, 1000, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 35, true, MIGRATION_34_35)

        // The row existed BEFORE this migration ran and named none of the four new columns - the
        // ALTER TABLE's own DEFAULT clause is what backfills it, matching WidgetInstance's Kotlin
        // defaults (gridRow=0, gridCol=0, rowSpan=1, colSpan=1) exactly.
        val cursor = db.query("SELECT gridRow, gridCol, rowSpan, colSpan FROM widget_instances WHERE deviceId = 'device-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        assertEquals(0, cursor.getInt(1))
        assertEquals(1, cursor.getInt(2))
        assertEquals(1, cursor.getInt(3))
        cursor.close()
    }

    @Test
    fun `migrate34To35_newColumnsAreWritable`() {
        helper.createDatabase(dbName, 34).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 35, true, MIGRATION_34_35)

        db.execSQL(
            "INSERT INTO widget_instances (deviceId, aspectId, recordTypeId, widgetType, config, position, gridRow, gridCol, rowSpan, colSpan, createdAt, updatedAt) " +
                "VALUES ('device-2', NULL, NULL, 'RECORD_LIST', '{}', 0, 3, 1, 2, 4, 1000, 1000)"
        )
        val cursor = db.query("SELECT gridRow, gridCol, rowSpan, colSpan FROM widget_instances WHERE deviceId = 'device-2'")
        assertTrue(cursor.moveToFirst())
        assertEquals(3, cursor.getInt(0))
        assertEquals(1, cursor.getInt(1))
        assertEquals(2, cursor.getInt(2))
        assertEquals(4, cursor.getInt(3))
        cursor.close()
    }
}
