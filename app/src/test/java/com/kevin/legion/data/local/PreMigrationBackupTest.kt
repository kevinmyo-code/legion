package com.kevin.legion.data.local

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Hardening ticket 03: a copy of the database is taken before Room migrates it. Robolectric
 * because the stored version is read through the platform's real SQLite, WAL included.
 */
@RunWith(RobolectricTestRunner::class)
class PreMigrationBackupTest {
    private lateinit var work: File
    private lateinit var dbFile: File
    private lateinit var root: File

    @Before
    fun setUp() {
        work = Files.createTempDirectory("premig").toFile()
        dbFile = File(work, "legion_database")
        root = File(work, "pre-migration-backups")
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun makeDb(version: Int, rows: Int = 3) {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE t (v TEXT)")
            repeat(rows) { db.execSQL("INSERT INTO t VALUES ('row$it')") }
            db.version = version
        }
    }

    private fun makeDbVersion(version: Int) {
        SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = version }
    }

    private fun rowsIn(file: File): Int =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM t", null).use { c -> c.moveToFirst(); c.getInt(0) }
        }

    private fun run(target: Int, now: Long) = PreMigrationBackup.backUpIfSchemaChanges(dbFile, root, target, now)

    @Test
    fun `no database file means a fresh install and no backup`() {
        assertEquals(PreMigrationBackup.Outcome.NotNeeded, run(target = 80, now = 1_000))
        assertTrue(PreMigrationBackup.status(root) is PreMigrationBackup.Status.None)
    }

    @Test
    fun `same version takes no backup`() {
        makeDb(version = 80)
        assertEquals(PreMigrationBackup.Outcome.NotNeeded, run(target = 80, now = 1_000))
        assertTrue(PreMigrationBackup.status(root) is PreMigrationBackup.Status.None)
    }

    @Test
    fun `an older stored version is copied before the migration, with its rows`() {
        makeDb(version = 79, rows = 5)
        val outcome = run(target = 80, now = 1_000)

        val taken = (outcome as PreMigrationBackup.Outcome.Taken).backup
        assertEquals(79, taken.fromVersion)
        assertEquals(80, taken.toVersion)
        assertEquals(1_000L, taken.takenAtMs)
        assertEquals(5, rowsIn(File(taken.dir, dbFile.name)))
        val status = PreMigrationBackup.status(root) as PreMigrationBackup.Status.Taken
        assertEquals(taken, status.latest)
    }

    @Test
    fun `the stored version is read through the WAL, not from the file header`() {
        // The version write sits only in the -wal while this connection stays open: a raw header
        // read would see 79, and every later launch would take a needless backup.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.enableWriteAheadLogging()
            db.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            db.execSQL("CREATE TABLE t (v TEXT)")
            db.version = 79
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.version = 80
            assertEquals("precondition: header still says 79", 79, headerUserVersion(dbFile))

            assertEquals(80, PreMigrationBackup.storedVersion(dbFile))
        }
    }

    @Test
    fun `wal and shm sidecars are copied with the main file`() {
        makeDb(version = 79)
        val wal = File(dbFile.path + "-wal").apply { writeText("wal") }
        val shm = File(dbFile.path + "-shm").apply { writeText("shm") }
        val taken = (run(target = 80, now = 1_000) as PreMigrationBackup.Outcome.Taken).backup

        // The version read may rewrite these, so compare against what they hold after the run.
        assertTrue(File(taken.dir, wal.name).readBytes().contentEquals(wal.readBytes()))
        assertTrue(File(taken.dir, shm.name).readBytes().contentEquals(shm.readBytes()))
    }

    @Test
    fun `an unreadable database is still copied, because Room may discard it`() {
        dbFile.writeText("not a database at all")
        val taken = (run(target = 80, now = 1_000) as PreMigrationBackup.Outcome.Taken).backup

        assertNull(taken.fromVersion)
        assertEquals("not a database at all", File(taken.dir, dbFile.name).readText())
        assertTrue("the read must not delete the file", dbFile.exists())
    }

    @Test
    fun `only the newest three are kept`() {
        makeDb(version = 79)
        for ((now, target) in listOf(1_000L to 80, 2_000L to 81, 3_000L to 82, 4_000L to 83)) run(target, now)

        val kept = PreMigrationBackup.list(root).map { it.takenAtMs }
        assertEquals(listOf(4_000L, 3_000L, 2_000L), kept)
    }

    @Test
    fun `stored version zero takes no copy`() {
        makeDb(version = 0)
        assertEquals(PreMigrationBackup.Outcome.NotNeeded, run(target = 80, now = 1_000))
    }

    @Test
    fun `starts of an older build take no copy and never push out the pre-migration copy`() {
        makeDb(version = 79)
        run(target = 80, now = 1_000)
        makeDbVersion(80)

        for (now in listOf(2_000L, 3_000L, 4_000L, 5_000L)) {
            assertEquals(PreMigrationBackup.Outcome.NotNeeded, run(target = 79, now = now))
        }

        val kept = PreMigrationBackup.list(root).map { Triple(it.takenAtMs, it.fromVersion, it.toVersion) }
        assertEquals(listOf(Triple(1_000L, 79, 80)), kept)
    }

    @Test
    fun `the same upgrade is copied once, however many starts see it`() {
        makeDb(version = 79)
        run(target = 80, now = 1_000)

        for (now in listOf(2_000L, 3_000L, 4_000L)) {
            assertEquals(PreMigrationBackup.Outcome.NotNeeded, run(target = 80, now = now))
        }
        assertEquals(listOf(1_000L), PreMigrationBackup.list(root).map { it.takenAtMs })
    }

    @Test
    fun `a failure outside the copy loop is still recorded, never shown as none yet`() {
        makeDb(version = 79)
        PreMigrationBackup.recordFailure(root, 2_000, "database path unavailable")

        val status = PreMigrationBackup.status(root) as PreMigrationBackup.Status.Failed
        assertEquals("database path unavailable", status.reason)
    }

    @Test
    fun `a half-written copy left by a killed process is never listed and is cleaned up`() {
        makeDb(version = 79)
        val stale = File(root, ".partial-500").apply { mkdirs() }
        File(stale, dbFile.name).writeText("half")

        run(target = 80, now = 1_000)

        assertTrue(!stale.exists())
        assertEquals(listOf(1_000L), PreMigrationBackup.list(root).map { it.takenAtMs })
    }

    private val failingCopy: (File, File) -> Unit = { _, _ -> throw java.io.IOException("No space left on device") }

    @Test
    fun `a failed copy is reported in words and leaves the last good backup listed`() {
        makeDb(version = 79)
        run(target = 80, now = 1_000)

        val outcome = PreMigrationBackup.backUpIfSchemaChanges(dbFile, root, 81, 2_000, failingCopy)

        assertEquals(PreMigrationBackup.Outcome.Failed("No space left on device"), outcome)
        val status = PreMigrationBackup.status(root) as PreMigrationBackup.Status.Failed
        assertEquals(2_000L, status.atMs)
        assertEquals("No space left on device", status.reason)
        assertEquals(1_000L, status.latest?.takenAtMs)
        val listed = PreMigrationBackup.list(root).map { it.takenAtMs }
        assertEquals("the half-written copy is not listed", listOf(1_000L), listed)
    }

    @Test
    fun `a later success clears the failure`() {
        makeDb(version = 79)
        PreMigrationBackup.backUpIfSchemaChanges(dbFile, root, 80, 2_000, failingCopy)

        run(target = 80, now = 3_000)

        assertTrue(PreMigrationBackup.status(root) is PreMigrationBackup.Status.Taken)
    }

    private fun headerUserVersion(file: File): Int = RandomAccessFile(file, "r").use { raf ->
        raf.seek(60)
        raf.readInt()
    }
}
