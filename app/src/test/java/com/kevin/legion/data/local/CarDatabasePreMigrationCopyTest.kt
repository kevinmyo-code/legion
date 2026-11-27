package com.kevin.legion.data.local

import android.database.sqlite.SQLiteDatabase
import com.kevin.legion.testutil.RoomTestReset
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Hardening ticket 03 through the real [CarDatabase.getDatabase]: the copy must hold the database
 * as it was BEFORE Room migrated it. Fails if the copy is unwired, or runs after Room opens.
 */
@RunWith(RobolectricTestRunner::class)
class CarDatabasePreMigrationCopyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val olderVersion = CarDatabase.SCHEMA_VERSION - 1

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        PreMigrationBackup.rootDir(context).deleteRecursively()
    }

    @After
    fun tearDown() {
        RoomTestReset.resetCarDatabaseSingleton()
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `the first open copies the older database before Room migrates it`() {
        val dbFile = context.getDatabasePath(CarDatabase.DATABASE_FILE_NAME)
        dbFile.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { it.version = olderVersion }

        val db = CarDatabase.getDatabase(context)
        val root = PreMigrationBackup.rootDir(context)
        assertTrue("building Room takes no copy; the first open does", PreMigrationBackup.list(root).isEmpty())
        // This bare file is not a real older schema, so Room's migration or validation may throw.
        // The copy is taken before either runs, which is the point.
        runCatching { db.openHelper.writableDatabase }

        val copy = PreMigrationBackup.list(root).single()
        assertEquals(olderVersion, copy.fromVersion)
        assertEquals(CarDatabase.SCHEMA_VERSION, copy.toVersion)
        val copied = File(copy.dir, CarDatabase.DATABASE_FILE_NAME)
        val copiedVersion = SQLiteDatabase.openDatabase(copied.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { it.version }
        assertEquals(olderVersion, copiedVersion)
    }
}
