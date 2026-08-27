package com.kevin.legion.sync

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Pins [DatabaseSnapshot.exportLocalCopy]'s two branches against a REAL Room-managed WAL
 * database (Robolectric, same shape as [com.kevin.legion.data.local.CarDatabaseFreshInstallTest]
 * - a fake/hand-rolled database would not exercise the real `openHelper`/WAL wiring this
 * exists to pin).
 *
 * **`VACUUM INTO` was spiked first, per CLAUDE.md's L10/L14 lesson ("run the spike before
 * porting the rest").** The spike (this test's first case) found it throws
 * `near "INTO": syntax error` under Robolectric 4.13's bundled SQLite (`sqlite4java`, a
 * statically-linked build that predates SQLite 3.27, the version `VACUUM INTO` shipped in).
 * That is a REPRODUCED failure of the exact code [DatabaseSnapshot] runs, not an inference
 * from documentation - see [DatabaseSnapshot]'s class doc comment for why this generalizes
 * to real risk on this app's minSdk 24 floor. Because of that finding, [DatabaseSnapshot]
 * does not depend on `VACUUM INTO` unconditionally: it tries it, and on ANY failure falls
 * back to `PRAGMA wal_checkpoint(TRUNCATE)` + a plain file copy. This test class pins BOTH
 * branches so a future SQLite/Robolectric upgrade that starts supporting `VACUUM INTO`
 * doesn't silently stop exercising the fallback this app's real minSdk floor still needs.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseSnapshotExportTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `VACUUM INTO is not supported by Robolectric's bundled SQLite - the spike this app's fallback exists for`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.categoryDao().allNames() // touch the DB so it's actually open

        val dest = File(context.cacheDir, "vacuum_spike.db")
        if (dest.exists()) dest.delete()
        val support = db.openHelper.writableDatabase

        val threw = try {
            support.execSQL("VACUUM INTO ?", arrayOf(dest.absolutePath))
            false
        } catch (t: Throwable) {
            true
        }
        assertTrue(
            "expected VACUUM INTO to fail under Robolectric's bundled SQLite (documents the " +
                "environment DatabaseSnapshot.exportLocalCopy's fallback protects against); if this " +
                "now passes, the fallback-branch assertion below still must hold on its own",
            threw,
        )
    }

    @Test
    fun `exportLocalCopy falls back to checkpoint+copy and produces a readable database file`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val before = db.categoryDao().allNames()
        assertTrue("expected a seeded category set to back up", before.isNotEmpty())

        val dest = File(context.cacheDir, "export_fallback_spike.db")
        if (dest.exists()) dest.delete()

        val method = DatabaseSnapshot.exportLocalCopy(context, dest)

        assertEquals(
            "Robolectric's bundled SQLite doesn't support VACUUM INTO (see the test above) - " +
                "exportLocalCopy should have taken the checkpoint+copy fallback branch",
            DatabaseSnapshot.ExportMethod.CHECKPOINT_COPY,
            method,
        )
        assertTrue("checkpoint+copy should have produced a file", dest.exists())
        assertTrue("checkpoint+copy output should be non-trivial in size", dest.length() > 0)

        // Open the copy as its own independent SQLite connection - proves the FILE is a
        // standalone, self-consistent database, not merely that Room's own pool can see it.
        val copy = android.database.sqlite.SQLiteDatabase.openDatabase(
            dest.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        try {
            val names = mutableListOf<String>()
            copy.rawQuery("SELECT name FROM categories ORDER BY name", null).use { c ->
                while (c.moveToNext()) names.add(c.getString(0))
            }
            assertEquals(before.sorted(), names.sorted())
        } finally {
            copy.close()
        }
    }
}
