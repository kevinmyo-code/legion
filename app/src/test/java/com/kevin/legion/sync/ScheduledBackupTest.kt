package com.kevin.legion.sync

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Backend-erp Phase 0 item 1, `.scratch/backend-erp/issues/05-migration-path.md`:
 * [ScheduledBackup] gives [DatabaseSnapshot] a scheduler. These tests drive [ScheduledBackup.runIfDue]
 * directly with injected `isAvailable`/`backup` seams (see that function's own doc comment for
 * why - a real [SyncCapability.syncAvailable]/[DatabaseSnapshot.backupNow] call needs live Play
 * Services and Drive network IO, neither of which this JVM/Robolectric test can provide) and an
 * injected clock, never a real sleep.
 *
 * [RobolectricTestRunner] only because [ScheduledBackup.prefs] calls
 * `context.applicationContext.getSharedPreferences`, which needs a real (shadowed) Context -
 * these tests never touch [CarDatabase] directly, but [RoomTestReset.resetCarDatabaseSingleton]
 * is still run in `@Before` per this ticket's own brief, for isolation from any other test that
 * shares this JVM process.
 */
@RunWith(RobolectricTestRunner::class)
class ScheduledBackupTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("scheduled_backup", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private val fakeGeneration = DatabaseSnapshot.Generation(
        timestampMs = 1L, schemaVersion = 1, rowCount = 10L, dbFileId = "d", metaFileId = "m",
    )

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
    fun `not available when sync is off - nothing recorded`() = runBlocking {
        val outcome = ScheduledBackup.runIfDue(
            context,
            now = 1_000_000L,
            isAvailable = { false },
            backup = { error("must not be called when unavailable") },
        )
        assertEquals(ScheduledBackup.Outcome.NotAvailable, outcome)
        assertNull(ScheduledBackup.lastSuccessAt(context))
        assertNull(ScheduledBackup.lastFailureReason(context))
    }

    @Test
    fun `not due when the last success is inside the 24h floor`() = runBlocking {
        val first = ScheduledBackup.runIfDue(
            context,
            now = 1_000_000L,
            isAvailable = { true },
            backup = { DatabaseSnapshot.BackupResult.Ok(fakeGeneration) },
        )
        assertTrue(first is ScheduledBackup.Outcome.BackedUp)
        assertEquals(1_000_000L, ScheduledBackup.lastSuccessAt(context))

        val second = ScheduledBackup.runIfDue(
            context,
            now = 1_000_000L + ScheduledBackup.MIN_INTERVAL_MS - 1,
            isAvailable = { true },
            backup = { error("must not be called before the floor elapses") },
        )
        assertEquals(ScheduledBackup.Outcome.NotDue, second)
        // Still the FIRST success timestamp - a not-due check must never touch it.
        assertEquals(1_000_000L, ScheduledBackup.lastSuccessAt(context))
    }

    @Test
    fun `due once the 24h floor has elapsed since the last success`() = runBlocking {
        ScheduledBackup.runIfDue(
            context,
            now = 1_000_000L,
            isAvailable = { true },
            backup = { DatabaseSnapshot.BackupResult.Ok(fakeGeneration) },
        )

        var ran = false
        val second = ScheduledBackup.runIfDue(
            context,
            now = 1_000_000L + ScheduledBackup.MIN_INTERVAL_MS,
            isAvailable = { true },
            backup = { ran = true; DatabaseSnapshot.BackupResult.Ok(fakeGeneration.copy(timestampMs = 2L)) },
        )
        assertTrue("expected backup() to actually run once the floor elapsed", ran)
        assertTrue(second is ScheduledBackup.Outcome.BackedUp)
        assertEquals(1_000_000L + ScheduledBackup.MIN_INTERVAL_MS, ScheduledBackup.lastSuccessAt(context))
    }

    @Test
    fun `a refused attempt records the reason and does not advance the last-success timestamp`() = runBlocking {
        // No prior success at all, so this run is due regardless of the 24h floor.
        val outcome = ScheduledBackup.runIfDue(
            context,
            now = 5_000L,
            isAvailable = { true },
            backup = { DatabaseSnapshot.BackupResult.Refused("looked like a wipe") },
        )
        assertEquals(ScheduledBackup.Outcome.Refused("looked like a wipe"), outcome)
        assertNull("a refused attempt must never count as a successful backup", ScheduledBackup.lastSuccessAt(context))
        assertEquals("looked like a wipe", ScheduledBackup.lastFailureReason(context))
    }

    @Test
    fun `a failed attempt records the reason and does not advance the last-success timestamp`() = runBlocking {
        val outcome = ScheduledBackup.runIfDue(
            context,
            now = 5_000L,
            isAvailable = { true },
            backup = { DatabaseSnapshot.BackupResult.Failed("upload failed") },
        )
        assertEquals(ScheduledBackup.Outcome.Failed("upload failed"), outcome)
        assertNull(ScheduledBackup.lastSuccessAt(context))
        assertEquals("upload failed", ScheduledBackup.lastFailureReason(context))
    }

    @Test
    fun `a later success clears a previously recorded failure reason`() = runBlocking {
        ScheduledBackup.runIfDue(
            context,
            now = 5_000L,
            isAvailable = { true },
            backup = { DatabaseSnapshot.BackupResult.Failed("upload failed") },
        )
        assertEquals("upload failed", ScheduledBackup.lastFailureReason(context))

        ScheduledBackup.runIfDue(
            context,
            now = 6_000L,
            isAvailable = { true },
            backup = { DatabaseSnapshot.BackupResult.Ok(fakeGeneration) },
        )
        assertNull("a success must clear the stale failure reason", ScheduledBackup.lastFailureReason(context))
        assertEquals(6_000L, ScheduledBackup.lastSuccessAt(context))
    }
}
