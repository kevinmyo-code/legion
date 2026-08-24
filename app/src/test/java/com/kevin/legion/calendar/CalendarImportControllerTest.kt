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
}
