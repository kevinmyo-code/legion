package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.UUID

/**
 * Coverage for [CalendarImportController] - ticket 19 point 2/6, rewritten for backend-erp ticket
 * 17's repoint ("RULED 2026-08-28": Dates now writes the local `events` table directly, no engine
 * involved).
 *
 * **What this file does NOT cover, and why, stated rather than silently thin (L11 discipline).** A
 * throwaway spike (written and run before this file, per "run the spike before porting the rest")
 * confirmed `CalendarContract`'s authority has no real backing `ContentProvider` registered under
 * Robolectric in this project - `ContentResolver.insert(Calendars.CONTENT_URI, ...)` returns a
 * `Uri` without throwing, but a subsequent `query` against that same URI returns `null`, and
 * [CalendarProvider.eventsInWindow]'s `Instances`-table read (a DIFFERENT authority path with no
 * shadow support at all) always comes back empty regardless of what was inserted. This is the same
 * class of gap CLAUDE.md sec 10 already documents for `LedgerController`/`PantryController`'s
 * DB-write paths ("Robolectric `ShadowContentResolver` mismatch, judged not worth chasing"), now
 * confirmed for `CalendarContract` specifically rather than assumed. The upsert-in-place and
 * Google-side-deletion-mirror behaviors [CalendarImportController.importNow] implements are
 * therefore UNTESTED here - `traced`, not `tested`, in the build report's assumptions ledger; a
 * real device is the only way to exercise them until this project adds its own fake
 * `ContentProvider` for the calendar authority.
 *
 * What CAN be exercised without a real backing provider, and is: the permission gate (unreadable
 * vs. a genuinely empty read - the exact distinction CLAUDE.md sec 1 names), the promise that an
 * import touching nothing at all never mistakes "no Google data reachable" for "delete everything
 * legion-authored", and [CalendarImportController.buildEventRow] - the pure half of the import,
 * DB-free by design specifically so it CAN be exercised directly.
 */
@RunWith(RobolectricTestRunner::class)
class CalendarImportControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // Robolectric does not auto-grant a manifest-declared permission for
        // ContextCompat.checkSelfPermission - CalendarProvider.hasReadPermission would otherwise
        // read as refused in every test here regardless of the manifest declaration, which is
        // exactly the "unreadable" case the dedicated denial test below exercises deliberately.
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.READ_CALENDAR)
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
    fun `a legion-authored appointment is never touched by an import that finds nothing to read`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = db.eventDao().insert(
            Event(
                serverId = UUID.randomUUID().toString(),
                title = "Dentist",
                startsAt = now + 3_600_000L,
                source = DatesAspectSeeder.SOURCE_LEGION,
                kind = EventKind.APPOINTMENT,
                updatedAtMs = now,
                createdAt = now,
            ),
        )

        // Permission is granted but there is nothing readable this run - must never be treated as
        // "everything legion-authored is now stale and gone".
        val outcome = CalendarImportController.importNow(context, now) as CalendarImportController.ImportOutcome.Imported

        assertEquals(0, outcome.created)
        assertEquals(0, outcome.updated)
        assertEquals(0, outcome.deleted)
        val row = db.eventDao().getById(id)!!
        assertTrue("the legion-authored row must stay live, completely untouched", !row.deleted)
        assertEquals("Dentist", row.title)
    }

    @Test
    fun `refused READ_CALENDAR reports unreadable, never an empty import`() = runBlocking {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.READ_CALENDAR)

        val outcome = CalendarImportController.importNow(context)

        assertEquals(CalendarImportController.ImportOutcome.PermissionMissing, outcome)
    }

    /**
     * [CalendarImportController.buildEventRow] is the pure half of the import (no DB, no
     * `ContentResolver`), added specifically so description/location/allDay/`LEGION::v1` handling
     * can be exercised directly even though [CalendarProvider.eventsInWindow] itself cannot be
     * under Robolectric - see this file's own class doc.
     */
    @Test
    fun `description, location and allDay round-trip into the right Event columns`() = runBlocking {
        val event = CalendarProvider.GoogleCalendarEvent(
            eventId = 42L,
            calendarId = 1L,
            title = "Team offsite",
            startMs = 1_000L,
            endMs = 2_000L,
            allDay = true,
            description = "Bring a laptop and a badge.",
            location = "  Conference Center  ",
        )

        val row = CalendarImportController.buildEventRow(event, existing = null, now = 5_000L)

        assertEquals("Team offsite", row.title)
        assertEquals(1_000L, row.startsAt)
        assertEquals(2_000L, row.endsAt)
        assertEquals(true, row.allDay)
        assertEquals("Conference Center", row.location)
        assertEquals("Bring a laptop and a badge.", row.notes)
        assertNull("plain prose carries no LEGION::v1 block", row.structuredMeta)
        assertEquals(EventKind.APPOINTMENT, row.kind)
        assertEquals(DatesAspectSeeder.SOURCE_GOOGLE, row.source)
        assertEquals("42@1000", row.googleEventId)
    }

    @Test
    fun `a LEGION v1 block is parsed into the structured column, not left as prose`() = runBlocking {
        val event = CalendarProvider.GoogleCalendarEvent(
            eventId = 7L,
            calendarId = 1L,
            title = "Midterm",
            startMs = 5_000L,
            endMs = 6_000L,
            allDay = false,
            description = "LEGION::v1\ncourse: COSC4320\nsource: canvas_verified\n---\nBring a calculator.",
            location = "",
        )

        val row = CalendarImportController.buildEventRow(event, existing = null, now = 10_000L)

        val parsed = JSONObject(row.structuredMeta!!)
        assertEquals("COSC4320", parsed.getString("course"))
        assertEquals("canvas_verified", parsed.getString("source"))
        assertEquals("Bring a calculator.", row.notes)
        assertNull("an unset EVENT_LOCATION must read as null, never an empty-string location", row.location)
    }

    @Test
    fun `an event with no description writes null, never an empty-string placeholder`() = runBlocking {
        val event = CalendarProvider.GoogleCalendarEvent(
            eventId = 9L,
            calendarId = 1L,
            title = "Quiet event",
            startMs = 10_000L,
            endMs = 11_000L,
            allDay = false,
            description = "",
            location = "",
        )

        val row = CalendarImportController.buildEventRow(event, existing = null, now = 20_000L)

        assertNull(row.notes)
        assertNull(row.structuredMeta)
        assertNull(row.location)
    }

    /** An update ([existing] non-null) preserves [Event.id]/[Event.serverId]/[Event.createdAt] -
     * the exact contract [CalendarImportController.buildEventRow]'s own doc comment describes,
     * proven directly rather than only through the DB-dependent upsert path this file cannot
     * exercise end-to-end under Robolectric. */
    @Test
    fun `updating an existing row preserves its id, serverId and createdAt`() = runBlocking {
        val existing = Event(
            id = 55L,
            serverId = "placeholder-uuid",
            title = "Old title",
            startsAt = 1_000L,
            source = DatesAspectSeeder.SOURCE_GOOGLE,
            kind = EventKind.APPOINTMENT,
            googleEventId = "42@1000",
            updatedAtMs = 1_000L,
            createdAt = 500L,
        )
        val event = CalendarProvider.GoogleCalendarEvent(
            eventId = 42L,
            calendarId = 1L,
            title = "New title",
            startMs = 1_000L,
            endMs = 2_000L,
            allDay = false,
            description = "",
            location = "",
        )

        val row = CalendarImportController.buildEventRow(event, existing = existing, now = 9_000L)

        assertEquals(55L, row.id)
        assertEquals("placeholder-uuid", row.serverId)
        assertEquals(500L, row.createdAt)
        assertEquals("New title", row.title)
        assertEquals(9_000L, row.updatedAtMs)
    }

    /**
     * The core claim of the unbounded mode: it must NOT reuse the windowed path's "candidate only
     * if its own start already falls in this run's window" guard. Both fixtures below are
     * `source=google`, `kind=appointment` rows already sitting in the local `events` table with no
     * matching key in a fresh (empty, since nothing is reachable under Robolectric per this file's
     * own class doc) read - a windowed run only reaches the one whose start is inside
     * `[now-30d, now+180d]`, an unbounded run must reach both.
     */
    @Test
    fun `unbounded run does not apply the windowed delete guard`() = runBlocking {
        val now = System.currentTimeMillis()

        val insideWindowId = db.eventDao().insert(
            Event(
                serverId = UUID.randomUUID().toString(),
                title = "Inside the standard window",
                startsAt = now + 3_600_000L,
                source = DatesAspectSeeder.SOURCE_GOOGLE,
                kind = EventKind.APPOINTMENT,
                googleEventId = "111@${now + 3_600_000L}",
                updatedAtMs = now,
                createdAt = now,
            ),
        )
        val farInThePast = now - CalendarImportController.WINDOW_PAST_MS - 400L * 24 * 60 * 60 * 1000L
        val outsideWindowId = db.eventDao().insert(
            Event(
                serverId = UUID.randomUUID().toString(),
                title = "Outside the standard window",
                startsAt = farInThePast,
                source = DatesAspectSeeder.SOURCE_GOOGLE,
                kind = EventKind.APPOINTMENT,
                googleEventId = "222@$farInThePast",
                updatedAtMs = now,
                createdAt = now,
            ),
        )

        val windowedOutcome =
            CalendarImportController.importNow(context, now, unbounded = false) as CalendarImportController.ImportOutcome.Imported
        assertEquals("only the in-window row is a deletion candidate for a windowed run", 1, windowedOutcome.deleted)
        assertTrue(
            "the in-window row is gone",
            db.eventDao().getById(insideWindowId)!!.deleted,
        )
        assertTrue(
            "a windowed run must never touch a row outside its own window",
            !db.eventDao().getById(outsideWindowId)!!.deleted,
        )

        val unboundedOutcome =
            CalendarImportController.importNow(context, now, unbounded = true) as CalendarImportController.ImportOutcome.Imported
        assertEquals(
            "the unbounded run must reach the row the windowed run could not",
            1,
            unboundedOutcome.deleted,
        )
        assertTrue(
            "unbounded delete semantics have no window guard to skip this row on",
            db.eventDao().getById(outsideWindowId)!!.deleted,
        )
    }

    // ------------------------------------------------------------- APPOINTMENT_ID_BASE disjointness
    // Coordinator follow-up round 2 on backend-erp ticket 17 (2026-08-28): a runtime collision
    // guard was judged not good enough for a reminder's id specifically (an armed AlarmManager
    // alarm, orphaned silently). These tests exercise the PROPERTY the disjoint range establishes,
    // not one example - see Event.APPOINTMENT_ID_BASE's own doc comment for the full account.

    /**
     * The property itself, exercised across MANY allocations and MANY pre-existing low-range rows
     * (simulating years of `records.id`-carried reminders, not just one), not a single example -
     * the coordinator's own framing. Every value [CalendarImportController.nextAppointmentId] ever
     * returns must sit at or above [Event.APPOINTMENT_ID_BASE], regardless of how many reminder-
     * shaped rows already occupy the low range or how many appointments already occupy the high one.
     */
    @Test
    fun `nextAppointmentId always allocates from the disjoint high range, regardless of table history`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        // A pile of reminder-shaped rows at low, records.id-like values - the low range this
        // allocator must never dip into, no matter how many of these exist.
        for (i in 1..500L) {
            db.eventDao().insert(
                Event(
                    id = i,
                    serverId = UUID.randomUUID().toString(),
                    title = "reminder $i",
                    startsAt = null,
                    source = DatesAspectSeeder.SOURCE_LEGION,
                    kind = EventKind.REMINDER,
                    updatedAtMs = 0L,
                    createdAt = 0L,
                ),
            )
        }

        val allocated = mutableListOf<Long>()
        repeat(50) {
            val id = CalendarImportController.nextAppointmentId(db)
            allocated += id
            // Mirrors upsertAll's own loop - each allocation must actually be consumed (inserted)
            // before the next call, or every call would just return the same value.
            db.eventDao().insert(
                Event(
                    id = id,
                    serverId = UUID.randomUUID().toString(),
                    title = "appointment",
                    startsAt = 1_000L,
                    source = DatesAspectSeeder.SOURCE_GOOGLE,
                    kind = EventKind.APPOINTMENT,
                    updatedAtMs = 0L,
                    createdAt = 0L,
                ),
            )
        }

        assertTrue(
            "every allocated id must sit at or above APPOINTMENT_ID_BASE, no exceptions",
            allocated.all { it >= Event.APPOINTMENT_ID_BASE },
        )
        assertEquals("ids allocated within one run must all be distinct", allocated.size, allocated.toSet().size)
    }

    @Test
    fun `nextAppointmentId starts exactly at APPOINTMENT_ID_BASE on an empty table`() = runBlocking {
        val db = CarDatabase.getDatabase(context)

        assertEquals(Event.APPOINTMENT_ID_BASE, CalendarImportController.nextAppointmentId(db))
    }

    /**
     * **Mutation-sensitive on purpose - a HARDCODED floor, not [Event.APPOINTMENT_ID_BASE] itself.**
     * Every other test in this file compares against the symbolic constant, which would keep
     * "passing" even if that constant were mutated down to something unsafe (e.g. `0L`) - the test
     * would just recompute its own expectation against the same broken value. This one pins a
     * literal, independent floor comfortably below the real base (100 million) but comfortably
     * above what a collapsed-to-zero base would produce, so a real mutation of the constant is
     * exactly what this assertion is built to catch. See the build report's own mutation-testing
     * note for the run where setting the base to `0L` made this fail.
     */
    @Test
    fun `nextAppointmentId never drops anywhere near the low, records-id-sized range - hardcoded floor, not the symbolic constant`() = runBlocking {
        val db = CarDatabase.getDatabase(context)

        assertTrue(
            "an appointment id must never be small enough to plausibly collide with a records.id",
            CalendarImportController.nextAppointmentId(db) >= 1_000_000L,
        )
    }

    /**
     * The reminder half of the property: a reminder carried at a `records.id` that an appointment
     * would previously (round 1's fix) have collided with must now keep that EXACT id, never
     * reassigned - because the appointment allocator no longer even considers that low value a
     * candidate. Builds the exact scenario the now-superseded collision test below used to exercise
     * (`id = 1` for both), except this time through the REAL allocator rather than a hand-picked id.
     */
    @Test
    fun `a reminder carried at a records-id an appointment would previously have collided with keeps that exact id`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        // The reminder side: seated at id = 1, exactly the value round 1's collision test proved
        // an appointment could previously have grabbed via plain autoincrement.
        db.eventDao().insert(
            Event(
                id = 1L,
                serverId = UUID.randomUUID().toString(),
                title = "Buy milk",
                startsAt = 60_000L,
                source = DatesAspectSeeder.SOURCE_LEGION,
                kind = EventKind.REMINDER,
                updatedAtMs = now,
                createdAt = now,
            ),
        )

        // The appointment side: allocated through the REAL allocator, exactly as upsertAll does.
        val appointmentId = CalendarImportController.nextAppointmentId(db)
        db.eventDao().insert(
            Event(
                id = appointmentId,
                serverId = UUID.randomUUID().toString(),
                title = "Dentist",
                startsAt = 50_000L,
                source = DatesAspectSeeder.SOURCE_GOOGLE,
                kind = EventKind.APPOINTMENT,
                updatedAtMs = now,
                createdAt = now,
            ),
        )

        assertEquals(
            "the reminder's carried id must be untouched - the allocator never reaches this low",
            "Buy milk",
            db.eventDao().getById(1L)!!.title,
        )
        assertTrue(
            "the appointment must land in the disjoint high range, nowhere near the reminder's id",
            appointmentId >= Event.APPOINTMENT_ID_BASE,
        )
        assertTrue("the two ids must never be equal", appointmentId != 1L)
    }
}
