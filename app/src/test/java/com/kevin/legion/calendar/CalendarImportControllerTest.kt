package com.kevin.legion.calendar

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Coverage for [CalendarImportController] - ticket 19 point 2/6.
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
 * vs. a genuinely empty read - the exact distinction CLAUDE.md sec 1 names), and the promise that
 * an import touching nothing at all never mistakes "no Google data reachable" for "delete
 * everything legion-authored".
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

    @Test
    fun `a legion-authored record is never touched by an import that finds nothing to read`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val now = System.currentTimeMillis()
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val legionResult = store.create(
            schema.recordTypeId,
            mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Dentist",
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 3_600_000L),
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
            ),
            RecordProvenance.USER,
            now,
        ) as RecordStore.WriteResult.Success

        // Permission is granted but there is nothing readable this run - must never be treated as
        // "everything legion-authored is now stale and gone".
        val outcome = CalendarImportController.importNow(context, now) as CalendarImportController.ImportOutcome.Imported

        assertEquals(0, outcome.created)
        assertEquals(0, outcome.updated)
        assertEquals(0, outcome.deleted)
        val legionRecord = db.engineRecordDao().getById(legionResult.recordId)!!
        assertNull("the legion-authored record must stay live, completely untouched", legionRecord.deletedAt)
        assertEquals(
            "Dentist",
            PayloadCodec.readString(JSONObject(legionRecord.payload), schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE)),
        )
    }

    @Test
    fun `refused READ_CALENDAR reports unreadable, never an empty import`() = runBlocking {
        shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.READ_CALENDAR)

        val outcome = CalendarImportController.importNow(context)

        assertEquals(CalendarImportController.ImportOutcome.PermissionMissing, outcome)
    }

    /**
     * [CalendarImportController.buildFieldValues] is the pure half of the import (no DB, no
     * `ContentResolver`), added specifically so description/location/allDay/`LEGION::v1` handling
     * can be exercised directly even though [CalendarProvider.eventsInWindow] itself cannot be
     * under Robolectric - see this file's own class doc.
     */
    @Test
    fun `description, location and allDay round-trip into the right engine fields`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val fieldIds = CalendarImportController.FieldIds.from(schema)
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

        val fieldValues = CalendarImportController.buildFieldValues(event, fieldIds)

        assertEquals("Team offsite", fieldValues[fieldIds.title])
        assertEquals(1_000L, fieldValues[fieldIds.start])
        assertEquals(2_000L, fieldValues[fieldIds.end])
        assertEquals(true, fieldValues[fieldIds.allDay])
        assertEquals("Conference Center", fieldValues[fieldIds.location])
        assertEquals("Bring a laptop and a badge.", fieldValues[fieldIds.notes])
        assertNull("plain prose carries no LEGION::v1 block", fieldValues[fieldIds.structuredMeta])
    }

    @Test
    fun `a LEGION v1 block is parsed into the structured field, not left as prose`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val fieldIds = CalendarImportController.FieldIds.from(schema)
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

        val fieldValues = CalendarImportController.buildFieldValues(event, fieldIds)

        val meta = fieldValues[fieldIds.structuredMeta] as String
        val parsed = JSONObject(meta)
        assertEquals("COSC4320", parsed.getString("course"))
        assertEquals("canvas_verified", parsed.getString("source"))
        assertEquals("Bring a calculator.", fieldValues[fieldIds.notes])
        assertNull("an unset EVENT_LOCATION must read as null, never an empty-string location", fieldValues[fieldIds.location])
    }

    @Test
    fun `an event with no description writes null, never an empty-string placeholder`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val fieldIds = CalendarImportController.FieldIds.from(schema)
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

        val fieldValues = CalendarImportController.buildFieldValues(event, fieldIds)

        assertNull(fieldValues[fieldIds.notes])
        assertNull(fieldValues[fieldIds.structuredMeta])
        assertNull(fieldValues[fieldIds.location])
    }

    /**
     * The core claim of the unbounded mode: it must NOT reuse the windowed path's "candidate only
     * if its own start already falls in this run's window" guard. Both records below are
     * `source=google` with no matching key in a fresh (empty, since nothing is reachable under
     * Robolectric per this file's own class doc) read - a windowed run only reaches the one whose
     * start is inside `[now-30d, now+180d]`, an unbounded run must reach both.
     */
    @Test
    fun `unbounded run does not apply the windowed delete guard`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val now = System.currentTimeMillis()
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

        val insideWindow = store.create(
            schema.recordTypeId,
            mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Inside the standard window",
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 3_600_000L),
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_GOOGLE,
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID) to "111@${now + 3_600_000L}",
            ),
            RecordProvenance.DETERMINISTIC,
            now,
        ) as RecordStore.WriteResult.Success

        val farInThePast = now - CalendarImportController.WINDOW_PAST_MS - 400L * 24 * 60 * 60 * 1000L
        val outsideWindow = store.create(
            schema.recordTypeId,
            mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Outside the standard window",
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to farInThePast,
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_GOOGLE,
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_GOOGLE_EVENT_ID) to "222@$farInThePast",
            ),
            RecordProvenance.DETERMINISTIC,
            now,
        ) as RecordStore.WriteResult.Success

        val windowedOutcome =
            CalendarImportController.importNow(context, now, unbounded = false) as CalendarImportController.ImportOutcome.Imported
        assertEquals("only the in-window row is a deletion candidate for a windowed run", 1, windowedOutcome.deleted)
        assertTrue(
            "the in-window row is gone",
            db.engineRecordDao().getById(insideWindow.recordId)!!.deletedAt != null,
        )
        assertNull(
            "a windowed run must never touch a row outside its own window",
            db.engineRecordDao().getById(outsideWindow.recordId)!!.deletedAt,
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
            db.engineRecordDao().getById(outsideWindow.recordId)!!.deletedAt != null,
        )
    }
}
