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
 * Instrumented migration test for v55 -> v56 (`voice_notes`,
 * `.scratch/voice-notes/issues/02-the-store.md`). Same shape as [CarDatabaseMigration8To9Test] -
 * a brand-new table, so the interesting assertions are "an unrelated pre-existing row survives
 * untouched" and "the new table's columns, including the nullable ones, actually work".
 *
 * **This test itself was never RUN this session** (no Gradle at all - a concurrent agent was
 * writing to the same build tree; see the session's own report), so treat it as compiled-only,
 * not executed. **`app/schemas/com.kevin.legion.data.local.CarDatabase/56.json` is the one piece
 * of ground truth this session DID have**, though - a real kapt run against this entity landed in
 * the tree mid-session (presumably triggered by another concurrent agent's build), and its
 * `createSql` for `voice_notes` is what [MIGRATION_55_56] was corrected to match verbatim (no SQL
 * `DEFAULT` on `provenance`/`interrupted`, unlike an earlier draft of that migration - see its own
 * doc comment). Whether that 56.json is itself durable ground truth or a contention artifact from
 * running the same version number as another concurrent ticket is unverified; the migration test
 * below still needs a real, exclusive `connectedAndroidTest` run to be trusted.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration55To56Test {
    private val dbName = "migration-test-55-56"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate55To56_createsVoiceNotesAndPreservesExistingData() {
        // Create the v55 database and insert one representative pre-existing row in an UNRELATED
        // table, to confirm the migration is purely additive and touches nothing else.
        helper.createDatabase(dbName, 55).apply {
            execSQL(
                "INSERT INTO sleep_logs (id, sleepDate, durationMinutes, quality, notes, loggedAt, trustTier, syncId) VALUES " +
                    "(1, 1733356800000, 450, 4, 'woke up once', 1733360000000, 'REPORTED', 'sync-sleep-1')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 56, true, MIGRATION_55_56)

        // The pre-existing, unrelated row survived untouched.
        val existing = db.query("SELECT notes FROM sleep_logs WHERE id = 1")
        assertTrue("expected the pre-migration row to still exist", existing.moveToFirst())
        assertEquals("woke up once", existing.getString(0))
        existing.close()

        // A complete, finished voice note can actually be written and read back through every
        // column, including the ones that stay null for the life of an in-progress recording.
        db.execSQL(
            "INSERT INTO voice_notes (id, serverId, startedAt, endedAt, title, summary, transcript, audioPath, kind, provenance, interrupted) VALUES " +
                "(1, NULL, 1000, 2000, 'Standup', 'Discussed the release date.', 'Full verbatim text.', '/data/voicenotes/a.m4a', 'MEETING', 'LLM_DERIVED', 0)"
        )
        val complete = db.query(
            "SELECT title, summary, transcript, kind, provenance, interrupted FROM voice_notes WHERE id = 1"
        )
        assertTrue(complete.moveToFirst())
        assertEquals("Standup", complete.getString(0))
        assertEquals("Discussed the release date.", complete.getString(1))
        assertEquals("Full verbatim text.", complete.getString(2))
        assertEquals("MEETING", complete.getString(3))
        assertEquals("LLM_DERIVED", complete.getString(4))
        assertEquals(0, complete.getInt(5))
        complete.close()

        // An in-progress recording - endedAt, title, summary and transcript all still null - is a
        // legal row, not a malformed one. This is the shape VoiceNoteRecorder.start() writes the
        // instant recording begins, before anything has been transcribed.
        db.execSQL(
            "INSERT INTO voice_notes (id, startedAt, endedAt, title, summary, transcript, audioPath, kind, provenance, interrupted) VALUES " +
                "(2, 3000, NULL, NULL, NULL, NULL, '/data/voicenotes/b.m4a', 'SOLO', 'LLM_DERIVED', 0)"
        )
        val inProgress = db.query("SELECT endedAt, title, summary, transcript FROM voice_notes WHERE id = 2")
        assertTrue(inProgress.moveToFirst())
        assertNull(inProgress.getString(0))
        assertNull(inProgress.getString(1))
        assertNull(inProgress.getString(2))
        assertNull(inProgress.getString(3))
        inProgress.close()
    }

    @Test
    fun migrate55To56_provenanceAndInterruptedHaveNoSqlDefault_mustBeSuppliedExplicitly() {
        // The opposite of what an earlier draft of this test asserted. VoiceNote.provenance and
        // VoiceNote.interrupted both carry KOTLIN default values, but neither has a
        // @ColumnInfo(defaultValue = ...) - so the generated SQL (confirmed against
        // app/schemas/com.kevin.legion.data.local.CarDatabase/56.json's own createSql, see
        // MIGRATION_55_56's own doc comment) declares them NOT NULL with no SQL-level DEFAULT.
        // Every ordinary write still gets "LLM_DERIVED"/false, because
        // com.kevin.legion.voice.VoiceNoteRecorder always constructs a full VoiceNote object and
        // Room's generated INSERT binds every column explicitly - this test is pinning the raw-SQL
        // boundary, not the app's real write path.
        helper.createDatabase(dbName, 55).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 56, true, MIGRATION_55_56)

        try {
            db.execSQL("INSERT INTO voice_notes (id, startedAt, kind) VALUES (1, 1000, 'SOLO')")
            org.junit.Assert.fail(
                "omitting provenance/interrupted must fail NOT NULL - there is no SQL-level " +
                    "default to silently fall back to"
            )
        } catch (expected: android.database.sqlite.SQLiteConstraintException) {
            // Expected: NOT NULL constraint failed.
        }

        db.execSQL(
            "INSERT INTO voice_notes (id, startedAt, kind, provenance, interrupted) VALUES " +
                "(1, 1000, 'SOLO', 'LLM_DERIVED', 0)"
        )
        val cursor = db.query("SELECT provenance, interrupted FROM voice_notes WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("LLM_DERIVED", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        cursor.close()
    }
}
