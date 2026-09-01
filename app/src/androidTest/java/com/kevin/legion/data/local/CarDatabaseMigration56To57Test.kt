package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v56 -> v57 (one-today ticket 08, "events are not todos",
 * `.scratch/one-today/issues/08-events-are-not-todos.md`). [MIGRATION_56_57] itself is data-only -
 * see its own doc comment for why the schema is untouched (`events.kind` already had no CHECK
 * constraint) and why the version bump is still required regardless.
 *
 * Three cases, matching the ticket's own required verification list:
 * 1. An `appointment` row that was ticked done (the real on-device `COSC 3334` row, 2026-09-01)
 *    becomes `kind = 'event'` with `done`/`doneAt` CLEARED, not merely hidden.
 * 2. An `appointment` row that was never ticked also becomes `event`, `done` staying `0`.
 * 3. A `reminder` row - ticked or not - is completely untouched: this migration's `WHERE` clause
 *    only ever touches `kind = 'appointment'`.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration56To57Test {
    private val dbName = "migration-test-56-57"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun insertEvent(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: Long,
        kind: String,
        done: Boolean,
        doneAt: Long?,
    ) {
        db.execSQL(
            "INSERT INTO events (id, serverId, title, startsAt, allDay, source, done, doneAt, " +
                "exact, exactDowngraded, updatedAtMs, kind) VALUES " +
                "($id, 'server-$id', 'Row $id', 1735689600000, 0, 'legion', ${if (done) 1 else 0}, " +
                "${doneAt ?: "NULL"}, 0, 0, 1735689600000, '$kind')"
        )
    }

    @Test
    fun migrate56To57_reclassifiesTickedAppointmentAndClearsDone() {
        helper.createDatabase(dbName, 56).apply {
            // The real shape found on-device 2026-09-01: a COSC 3334 appointment row ticked done
            // during testing, which must not survive as a stale true on a row that becomes an event.
            insertEvent(this, id = 1, kind = "appointment", done = true, doneAt = 1735700000000)
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 57, true, MIGRATION_56_57)

        val cursor = db.query("SELECT kind, done, doneAt FROM events WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("event", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        assertNull(cursor.getString(2))
        cursor.close()
    }

    @Test
    fun migrate56To57_reclassifiesUntickedAppointment() {
        helper.createDatabase(dbName, 56).apply {
            insertEvent(this, id = 2, kind = "appointment", done = false, doneAt = null)
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 57, true, MIGRATION_56_57)

        val cursor = db.query("SELECT kind, done, doneAt FROM events WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals("event", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        assertNull(cursor.getString(2))
        cursor.close()
    }

    @Test
    fun migrate56To57_leavesReminderRowsCompletelyUntouched() {
        helper.createDatabase(dbName, 56).apply {
            // A genuinely completed reminder - this migration's WHERE clause must never touch it,
            // even though it shares the same table and the same done/doneAt columns.
            insertEvent(this, id = 3, kind = "reminder", done = true, doneAt = 1735700000000)
            insertEvent(this, id = 4, kind = "reminder", done = false, doneAt = null)
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 57, true, MIGRATION_56_57)

        val doneReminder = db.query("SELECT kind, done, doneAt FROM events WHERE id = 3")
        assertTrue(doneReminder.moveToFirst())
        assertEquals("reminder", doneReminder.getString(0))
        assertEquals(1, doneReminder.getInt(1))
        assertEquals(1735700000000, doneReminder.getLong(2))
        doneReminder.close()

        val openReminder = db.query("SELECT kind, done FROM events WHERE id = 4")
        assertTrue(openReminder.moveToFirst())
        assertEquals("reminder", openReminder.getString(0))
        assertEquals(0, openReminder.getInt(1))
        openReminder.close()
    }
}
