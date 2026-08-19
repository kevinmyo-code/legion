package com.kevin.legion.sync

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ravi's review (2026-08-13, BLOCKING finding 3): restore had zero automated coverage.
 * These run against a REAL Room-managed database via Robolectric (same shape as
 * [DatabaseSnapshotExportTest]/[com.kevin.legion.data.local.CarDatabaseFreshInstallTest]) -
 * a fake/hand-rolled database would not exercise [CarDatabase.getDatabase]'s singleton,
 * [CarDatabase.closeAndClear]/[CarDatabase.withDatabaseLock], or the real on-disk file paths
 * [DatabaseSnapshot] operates on.
 *
 * These exercise [DatabaseSnapshot]'s `restoreFromLocal(..., PRE_RESTORE_COPY)` path rather
 * than [DatabaseSnapshot.restore] directly - `restore()` needs a live Drive session
 * ([DriveAuth]/[SyncCapability] both require Play Services), which Robolectric cannot provide
 * without heavy additional mocking (same class of gap CLAUDE.md §10 already accepts for
 * `LedgerController`/`PantryController`'s DB-write paths). The install/rollback MECHANICS -
 * [installDatabaseFile] and [rollbackTo], accessed here via `restoreFromLocal`'s
 * `PRE_RESTORE_COPY` branch, which calls the exact same private `installDatabaseFile` - are
 * identical either way; only how the source file arrives (network download vs. already on
 * disk) differs, and that seam is deliberately outside what these tests need to touch.
 */
@RunWith(RobolectricTestRunner::class)
class DatabaseSnapshotRestoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // Belt-and-braces against cross-test leakage of the on-disk artifacts these tests
        // create/consume - Robolectric resets its SQLite shadow per test method, but plain
        // java.io.File state under context.filesDir/cacheDir is NOT reset automatically.
        File(context.filesDir, "pre_restore_backups").deleteRecursively()
        File(context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME).path + ".replaced-by-restore").delete()
    }

    /** Writes a real, independently-openable SQLite file with a `probe` table holding one
     * marker row, at [dest] - a synthetic-but-valid "backup" distinguishable from whatever
     * CarDatabase itself contains (which has no `probe` table). */
    private fun writeSyntheticDatabase(dest: File, markerValue: String) {
        if (dest.exists()) dest.delete()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dest, null)
        try {
            db.execSQL("CREATE TABLE probe (value TEXT NOT NULL)")
            db.execSQL("INSERT INTO probe (value) VALUES (?)", arrayOf(markerValue))
        } finally {
            db.close()
        }
    }

    private fun probeValue(dbFile: File): String? {
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            db.rawQuery("SELECT value FROM probe LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun `restoring a valid local database replaces the live file and the data actually changes`() = runBlocking {
        // Touch CarDatabase first so there is a real live file at the expected path with real
        // (non-probe) content - this is what a restore must overwrite.
        val before = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertTrue("expected a seeded category set before restoring over it", before.isNotEmpty())

        val recoveryDir = File(context.filesDir, "pre_restore_backups").apply { mkdirs() }
        val syntheticBackup = File(recoveryDir, "pre_restore_1.db")
        writeSyntheticDatabase(syntheticBackup, markerValue = "restored-marker")

        val recovery = DatabaseSnapshot.LocalRecovery(
            file = syntheticBackup, timestampMs = 1L,
            kind = DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY, label = "test",
        )
        val result = DatabaseSnapshot.restoreFromLocal(context, recovery)

        assertEquals(DatabaseSnapshot.RestoreResult.Ok, result)

        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        assertTrue("live db file should exist after a successful restore", liveDb.exists())
        assertEquals(
            "the live file's DATA should actually be the restored content, not just present",
            "restored-marker",
            probeValue(liveDb),
        )

        // The moved-aside original should be cleaned up on a clean success, and Room should
        // be able to open the NEW file fresh (this is what CarDatabase.getDatabase does on
        // the driver's next screen after the required restart).
        assertFalse(
            File(liveDb.path + ".replaced-by-restore").exists(),
        )
    }

    @Test
    fun `a failed install rename rolls back to the pre-restore safety copy and the original is still openable`() = runBlocking {
        val before = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertTrue(before.isNotEmpty())

        val recoveryDir = File(context.filesDir, "pre_restore_backups").apply { mkdirs() }
        val syntheticBackup = File(recoveryDir, "pre_restore_2.db")
        writeSyntheticDatabase(syntheticBackup, markerValue = "should-not-install")

        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)

        // Force `sourceFile.renameTo(liveDb)` to fail without depending on any
        // platform-specific SecurityException plumbing: hold an exclusive lock on the
        // SOURCE file for the duration of the restore attempt. This test runs on Windows
        // (this repo's dev environment), where an open file handle reliably blocks a
        // rename/move of that same file by another handle - the OS itself refuses the
        // operation, giving a real (not simulated) renameTo failure.
        //
        // WINDOWS-DEPENDENT, and this test QUIETLY STOPS TESTING ANYTHING if that changes
        // (senior-dev review, 2026-08-12). Windows enforces mandatory locking on an open
        // handle; POSIX does not - `rename()` there does not consult open descriptors, and
        // this RandomAccessFile takes no advisory FileChannel.lock(). So on a Linux or macOS
        // runner the rename would SUCCEED, the install would report success, and the
        // rollback branch this test exists to prove would never execute - while the test
        // itself might still pass for the wrong reason. Today's only runner is Kevin's
        // Windows box (CLAUDE.md §6), so this holds. Anyone moving CI off Windows must
        // replace this mechanism (inject a failing install step) rather than assume the
        // green tick still means the rollback path works.
        val lock = java.io.RandomAccessFile(syntheticBackup, "rw")
        try {
            val recovery = DatabaseSnapshot.LocalRecovery(
                file = syntheticBackup, timestampMs = 2L,
                kind = DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY, label = "test",
            )
            val result = DatabaseSnapshot.restoreFromLocal(context, recovery)

            assertTrue(
                "expected the install to be reported as Failed when the source cannot be renamed into place",
                result is DatabaseSnapshot.RestoreResult.Failed,
            )
        } finally {
            lock.close()
        }

        // The critical assertion: the live database must still be OPENABLE AND READABLE after
        // the failed install - rolled back from the verified-consistent safety copy, not left
        // as a half-installed mess (or, worse, gone entirely).
        assertTrue("live db file should exist after a rolled-back failed restore", liveDb.exists())
        val afterRollback = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertEquals(
            "the rolled-back database should have the SAME category data as before the attempt",
            before.sorted(),
            afterRollback.sorted(),
        )

        // And no stray moved-aside file should remain, since the rollback path deletes it on
        // a successful copy-back (see DatabaseSnapshot.rollbackTo).
        assertFalse(File(liveDb.path + ".replaced-by-restore").exists())
    }

    @Test
    fun `installDatabaseFile refuses when a previous restore left an unresolved artifact, rather than deleting it`() = runBlocking {
        CarDatabase.getDatabase(context).categoryDao().allNames()

        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        val leftover = File(liveDb.path + ".replaced-by-restore")
        writeSyntheticDatabase(leftover, markerValue = "leftover-from-prior-attempt")

        val recoveryDir = File(context.filesDir, "pre_restore_backups").apply { mkdirs() }
        val syntheticBackup = File(recoveryDir, "pre_restore_3.db")
        writeSyntheticDatabase(syntheticBackup, markerValue = "should-not-install")

        val recovery = DatabaseSnapshot.LocalRecovery(
            file = syntheticBackup, timestampMs = 3L,
            kind = DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY, label = "test",
        )
        val result = DatabaseSnapshot.restoreFromLocal(context, recovery)

        assertTrue(result is DatabaseSnapshot.RestoreResult.Failed)
        // The leftover must survive untouched - this is the "do not silently delete it"
        // requirement (Ravi's review, BLOCKING finding 6).
        assertTrue("the leftover .replaced-by-restore artifact must not be deleted by a refused attempt", leftover.exists())
        assertEquals("leftover-from-prior-attempt", probeValue(leftover))
    }

    @Test
    fun `listLocalRecoveries surfaces both pre-restore copies and an interrupted-restore leftover`() = runBlocking {
        val recoveryDir = File(context.filesDir, "pre_restore_backups").apply { mkdirs() }
        writeSyntheticDatabase(File(recoveryDir, "pre_restore_100.db"), "a")
        writeSyntheticDatabase(File(recoveryDir, "pre_restore_200.db"), "b")

        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        val leftover = File(liveDb.path + ".replaced-by-restore")
        writeSyntheticDatabase(leftover, "c")

        val recoveries = DatabaseSnapshot.listLocalRecoveries(context)

        assertEquals(3, recoveries.size)
        assertTrue(recoveries.any { it.kind == DatabaseSnapshot.LocalRecoveryKind.INTERRUPTED_ORIGINAL })
        assertEquals(2, recoveries.count { it.kind == DatabaseSnapshot.LocalRecoveryKind.PRE_RESTORE_COPY })
    }

    @Test
    fun `restoreFromLocal can recover an interrupted-restore leftover directly`() = runBlocking {
        val before = CarDatabase.getDatabase(context).categoryDao().allNames()
        assertTrue(before.isNotEmpty())

        val liveDb = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        val leftover = File(liveDb.path + ".replaced-by-restore")
        writeSyntheticDatabase(leftover, "recovered-original")

        val recovery = DatabaseSnapshot.LocalRecovery(
            file = leftover, timestampMs = 9L,
            kind = DatabaseSnapshot.LocalRecoveryKind.INTERRUPTED_ORIGINAL, label = "test",
        )
        val result = DatabaseSnapshot.restoreFromLocal(context, recovery)

        assertEquals(DatabaseSnapshot.RestoreResult.Ok, result)
        assertEquals("recovered-original", probeValue(liveDb))
        assertFalse(leftover.exists())
    }
}
