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
 * Instrumented migration test for v25 -> v26 - `music_play_history`, LEGION's own
 * observed-listening log (ticket 05,
 * `.scratch/drive-test-2026-08-18/issues/05-reading-kevins-spotify-library.md`). See
 * [MIGRATION_25_26]'s own doc comment for the full story: one additive `CREATE TABLE`,
 * `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/26.json` after a kapt run rather than
 * assumed - byte for byte the same string this migration's own `execSQL` call uses.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. On-device evidence is the database
 * opening after install and a real row landing after a drive with music playing, both deferred to
 * on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration25To26Test {
    private val dbName = "migration-test-25-26"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate25To26_createsAnEmptyMusicPlayHistoryTable`() {
        helper.createDatabase(dbName, 25).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 26, true, MIGRATION_25_26)

        val cursor = db.query("SELECT COUNT(*) FROM music_play_history")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun `migrate25To26_freshInsertRoundTripsEveryColumn`() {
        helper.createDatabase(dbName, 25).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 26, true, MIGRATION_25_26)

        db.execSQL(
            "INSERT INTO music_play_history " +
                "(title, artist, album, spotifyUri, startedAt, startedByLegion) " +
                "VALUES ('Plastic Love', 'Mariya Takeuchi', 'Variety', 'spotify:track:abc123', 1000, 1)"
        )
        val cursor = db.query(
            "SELECT title, artist, album, spotifyUri, startedByLegion " +
                "FROM music_play_history WHERE startedAt = 1000"
        )
        assertTrue(cursor.moveToFirst())
        assertEquals("Plastic Love", cursor.getString(0))
        assertEquals("Mariya Takeuchi", cursor.getString(1))
        assertEquals("Variety", cursor.getString(2))
        assertEquals("spotify:track:abc123", cursor.getString(3))
        assertEquals(1, cursor.getInt(4))
        cursor.close()
    }

    @Test
    fun `migrate25To26_spotifyUriIsNullableForANonSpotifySource`() {
        // MusicPlayHistoryEntry.spotifyUri is nullable by design - AVRCP/Bluetooth relayed
        // metadata routinely carries no URI at all. Confirms the column itself accepts and
        // round-trips NULL, not just that the Kotlin type says it should.
        helper.createDatabase(dbName, 25).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 26, true, MIGRATION_25_26)

        db.execSQL(
            "INSERT INTO music_play_history " +
                "(title, artist, album, spotifyUri, startedAt, startedByLegion) " +
                "VALUES ('Some Track', 'Some Artist', '', NULL, 2000, 0)"
        )
        val cursor = db.query("SELECT spotifyUri FROM music_play_history WHERE startedAt = 2000")
        assertTrue(cursor.moveToFirst())
        assertNull(cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `migrate25To26_startedByLegionDefaultsToFalseWhenOmitted`() {
        // Confirms the ADD COLUMN-equivalent CREATE TABLE's DEFAULT 0 is real at the SQLite
        // level, matching MusicPlayHistoryEntry's @ColumnInfo(defaultValue = "0").
        helper.createDatabase(dbName, 25).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 26, true, MIGRATION_25_26)

        db.execSQL(
            "INSERT INTO music_play_history (title, artist, album, spotifyUri, startedAt) " +
                "VALUES ('Some Track', 'Some Artist', 'Some Album', NULL, 3000)"
        )
        val cursor = db.query("SELECT startedByLegion FROM music_play_history WHERE startedAt = 3000")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }
}
