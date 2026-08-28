package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [CarDatabase.SCHEMA_VERSION] must equal the version Room actually opens the database at.
 *
 * **This exists because "must be bumped by hand" failed twice, and the second time cost a real
 * recovery.** The constant mirrors `@Database(version = ...)` because a Composable cannot read an
 * annotation value at Kotlin compile time, so the version is deliberately written in two places.
 * Tickets 17 and 18 each moved the annotation to 48 and then 49 and left the constant at 47.
 *
 * The constant's own doc comment said a forgotten bump "only ever makes the UI's restore button
 * MORE conservative... never less", which reads as harmless and is not.
 * `DriveBackupResolver.generationRows` compares a backup's recorded schema version against this
 * constant and refuses to offer a restore for anything NEWER. At 47, every v49 backup - which is
 * to say every backup taken by the running app, the only ones whose shape actually matched the
 * live schema - was reported as "from a newer app version than this one" and had its restore
 * button disabled. That was discovered on 2026-08-28 in the middle of the first real restore
 * drill, on a phone that had just been rolled back and needed exactly that button.
 *
 * More conservative is not the same as safe. A backup nobody can restore is not a backup.
 *
 * Robolectric rather than a plain unit test, and deliberately through
 * [CarDatabase.getDatabase] rather than an in-memory builder: the number that matters is the one
 * Room writes into `PRAGMA user_version` on the REAL database this app opens, which is also
 * exactly what `DatabaseSnapshot` reads out of a backup file. Comparing the constant against a
 * hand-written literal here would just be a third place to forget.
 */
@RunWith(RobolectricTestRunner::class)
class CarDatabaseSchemaVersionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `SCHEMA_VERSION equals the version Room opens the database at`() {
        val db = CarDatabase.getDatabase(context)
        val openedVersion = db.openHelper.readableDatabase.version

        assertEquals(
            "CarDatabase.SCHEMA_VERSION (${CarDatabase.SCHEMA_VERSION}) disagrees with the " +
                "version Room actually opens the database at ($openedVersion). Bump the constant " +
                "to match `@Database(version = ...)`. This is not cosmetic: DriveSyncScreen uses " +
                "the constant to decide whether a backup may be restored at all, and a stale " +
                "lower value silently disables restore on every backup the running app produces.",
            openedVersion,
            CarDatabase.SCHEMA_VERSION,
        )
    }
}
