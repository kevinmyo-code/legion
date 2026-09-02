package com.kevin.legion.data.local

import com.kevin.legion.backend.EventKind
import com.kevin.legion.testutil.RoomTestReset
import java.util.UUID
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
 * [MIGRATION_57_58] - "purge the persistent list, and stop what refills it" (2026-09-01). Exercises
 * the REAL [Migration.migrate] SQL directly against a real, fully-schema'd `events` table (via
 * [CarDatabase.getDatabase]'s own [androidx.room.RoomDatabase.openHelper], not a hand-rolled table)
 * rather than going through Room's actual version-upgrade path - this codebase has no
 * `androidx.room.testing.MigrationTestHelper` wired into unit tests (only `androidTestImplementation`,
 * per `app/build.gradle.kts`), and a data-only migration's DELETE statements are exactly as
 * meaningful run against the current schema as they are mid-upgrade, since [MIGRATION_57_58]
 * touches no column or table shape at all (see its own doc comment).
 *
 * Fixture rows mirror the real, on-device figures the migration's own doc comment cites: 12 named
 * ids among the calendar duplicates, several `"Plan: "`-prefixed checklist lines (multiplied, like
 * the real `Plan: Sleep 8h` x4), and the 6 explicitly-kept reminders by their real text.
 */
@RunWith(RobolectricTestRunner::class)
class Migration57To58Test {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun insertReminder(id: Long, title: String): Long {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val row = Event(
            id = id,
            serverId = UUID.randomUUID().toString(),
            guid = UUID.randomUUID().toString(),
            title = title,
            startsAt = null,
            allDay = false,
            source = "legion",
            kind = EventKind.REMINDER,
            updatedAtMs = now,
            createdAt = now,
        )
        return db.eventDao().insert(row)
    }

    @Test
    fun `deletes the 12 named calendar duplicates and every Plan line, keeps everything else`() = runBlocking {
        val db = CarDatabase.getDatabase(context)

        val duplicateIds = listOf(
            100000154L, 100000155L, 100000156L, 100000157L, 100000158L, 100000159L,
            100000160L, 100000161L, 100000162L, 100000163L, 100000165L, 100000166L,
        )
        duplicateIds.forEach { id -> insertReminder(id, "Google duplicate $id") }

        val planLines = listOf(
            "Plan: Hit 2300 kcal / 180g protein", "Plan: Hit 2300 kcal / 180g protein",
            "Plan: Hit 2300 kcal / 180g protein", "Plan: Hit 2300 kcal / 180g protein",
            "Plan: Sleep 8h", "Plan: Sleep 8h", "Plan: Sleep 8h", "Plan: Sleep 8h",
        )
        val planIds = planLines.mapIndexed { i, text -> insertReminder(200_000L + i, text) }

        val keptTexts = listOf(
            "fix fuel pump relay fault before the road trip",
            "follow up with financial aid from my scholarship",
            "Schedule annual health checkup for myself",
            "Schedule annual health checkup for my wife",
            "buy toilet seat screw",
            "school work",
        )
        val keptIds = keptTexts.mapIndexed { i, text -> insertReminder(700_000L + i, text) }

        // Run the REAL migration SQL directly against the real, live `events` table.
        MIGRATION_57_58.migrate(db.openHelper.writableDatabase)

        val remaining = db.eventDao().getAll().filter { !it.deleted }

        duplicateIds.forEach { id ->
            assertTrue("duplicate id $id must be gone", remaining.none { it.id == id })
        }
        planIds.forEach { id ->
            assertTrue("plan line id $id must be gone", remaining.none { it.id == id })
        }
        assertTrue("no Plan: line may survive", remaining.none { it.title.startsWith("Plan: ") })
        keptIds.forEach { id ->
            assertTrue("kept row $id must survive", remaining.any { it.id == id })
        }
        assertEquals(keptTexts.size, remaining.size)
    }
}
