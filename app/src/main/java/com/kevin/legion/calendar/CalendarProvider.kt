package com.kevin.legion.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * Provider layer over Android's on-device `CalendarContract` (ticket 13,
 * `.scratch/google-account-integration/issues/13-calendar-read.md`, decided by
 * `.scratch/google-account-integration/issues/02-calendar-api-choice.md`: `CalendarContract`, never
 * the Calendar REST API - no OAuth scope, no console, no consent screen, offline for free, and its
 * `Instances` table already expands a recurring series into occurrences for us, so nothing here
 * re-implements RRULE). Ticket 14 (`.scratch/google-account-integration/issues/14-calendar-write.md`)
 * added [insertEvent] and [hasWritePermission] onto this same file rather than forking a second one -
 * read and write are the same provider boundary. Ticket 22
 * (`.scratch/google-account-integration/issues/22-edit-calendar-entries-from-log.md`) adds
 * [updateEventSeries]/[updateEventOccurrence]/[deleteEventSeries]/[deleteEventOccurrence] the same
 * way - editing Google's own copy in place, never copying an event into Room (ticket 04/22's own
 * framing of why this does not reopen the "nothing is ever written to both stores" rule).
 *
 * **Nothing is stored.** No Room entity, no DAO, no cache - every call below re-reads the platform
 * provider at render time, and [insertEvent] hands back only the id the provider itself just
 * assigned, which the caller must not persist. This is ticket 04's answer, point 5, applied: "the
 * local table stores NOTHING about a Google event... no reconciliation, and nothing to lose." The
 * provider already IS Google Calendar's own local cache, maintained by Play Services; a second one
 * would only add drift LEGION would then have to keep in sync with itself. If a future change here
 * starts looking like it wants a table, that is a sign the change has misread ticket 04, not a sign
 * to add one.
 *
 * Every function degrades to empty/null rather than throwing when the relevant permission is
 * refused, when the device has no `com.google` account synced, or when Calendar sync is toggled off
 * for that account (research doc §1.5, both `needs-a-spike` on the actual device but neither fatal -
 * they just mean nothing to read/write). **Turning "empty"/`null` into a worded reason is the
 * caller's job** (`ui/TodayScreen.kt`/`ui/notes/CalendarAgendaResolver.kt` for reads,
 * `service/LiveToolbox.kt` for the write) not this file's - a provider that also decided how its own
 * silence should read would be answering a UI question from inside the data layer, the same class
 * of drift ticket 04 was written to prevent for storage.
 */
object CalendarProvider {

    /** True when `READ_CALENDAR` is granted. Every read below checks this itself (so a caller that
     * forgets to gate a call still gets an honest empty list, never a `SecurityException`), but the
     * screen should also check it directly to tell "refused" apart from "genuinely nothing". */
    fun hasReadPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /** True when `WRITE_CALENDAR` is granted. [insertEvent] checks this itself, same shape as
     * [hasReadPermission] - a caller that forgets to gate a write still gets an honest `null`,
     * never a `SecurityException`. */
    fun hasWritePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One `com.google` calendar row from the `Calendars` table. Carries [accessLevel] verbatim so a
     * caller that needs to distinguish writable from read-only (as [writableGoogleCalendars] does)
     * can, without a second query - `CAL_ACCESS_CONTRIBUTOR` is 500.
     */
    data class GoogleCalendar(
        val id: Long,
        val accountName: String,
        val displayName: String,
        val isPrimary: Boolean,
        val accessLevel: Int,
    )

    /** One event OCCURRENCE, already expanded from any recurrence by `Instances` (research doc
     * §1/§5) - a repeating Google event contributes one row per occurrence in the queried window,
     * never a raw `RRULE` string this file would have to parse. */
    data class GoogleCalendarEvent(
        val eventId: Long,
        val calendarId: Long,
        val title: String,
        val startMs: Long,
        val endMs: Long,
        val allDay: Boolean,
        /** True when the PARENT event carries an `RRULE` or `RDATE` - ticket 22's "this one or all
         * of them" prompt only makes sense when there is a series to choose between. Read straight
         * off `Instances`' own `RRULE`/`RDATE` columns (inherited from `EventsColumns`, the same
         * interface `Events` itself implements), never recomputed - this file never re-derives
         * recurrence, per its own class doc comment. Defaults false so existing call sites/fixtures
         * that predate ticket 22 keep compiling unchanged. */
        val recurring: Boolean = false,
        /** The owning calendar's own `CALENDAR_ACCESS_LEVEL`, carried alongside the event so a
         * caller deciding whether to offer an edit (ticket 22 point 4 - Kevin's read-only "Holidays
         * in United States" calendar) never needs a second query - the same "carry what a caller
         * needs, do not force a round trip" precedent [GoogleCalendar.accessLevel] already sets.
         * `>= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR` (500) is writable. Defaults to
         * CONTRIBUTOR so existing fixtures/call sites read as writable unless they say otherwise. */
        val calendarAccessLevel: Int = CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
        /** The event's own `DESCRIPTION`, verbatim and untrimmed, empty when the column is null.
         * `Instances` inherits it from `EventsColumns` exactly as [recurring]'s `RRULE`/`RDATE` do,
         * so this costs one more projection column and no second query. Carried because a
         * description is where an event states what a title has no room for - points, late policy,
         * and (see `read_calendar`'s own doc) the provenance of the date itself. Defaults empty so
         * every existing fixture and call site that predates this field keeps compiling, the same
         * precedent [recurring] and [calendarAccessLevel] already set. */
        val description: String = "",
        /** The event's own `EVENT_LOCATION`, empty when null. Same inheritance and same
         * default-for-compatibility reasoning as [description]. */
        val location: String = "",
    )

    /**
     * Every `com.google` calendar on the device, straight from the `Calendars` table, no access-level
     * floor - `ACCOUNT_TYPE = "com.google"` only. This is the READ set (ticket 17): a calendar Kevin
     * can only read (a subscribed public holidays feed, a shared calendar he does not own,
     * `CALENDAR_ACCESS_LEVEL = CAL_ACCESS_READ = 200`) still belongs on his agenda. Empty (never an
     * exception) when `READ_CALENDAR` is refused or the device has nothing matching.
     */
    fun allGoogleCalendars(context: Context): List<GoogleCalendar> {
        if (!hasReadPermission(context)) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val selection = "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val args = arrayOf("com.google")
        val out = mutableListOf<GoogleCalendar>()
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val nameCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
            val displayCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
            val accessCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            // IS_PRIMARY is queried defensively, not assumed present - an aggressive OEM sync
            // adapter variant is exactly the kind of thing the Oppo A17K has already surprised this
            // project with once (auto-memory: it filters LEGION's own logcat).
            val primaryCol = c.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
            while (c.moveToNext()) {
                out.add(
                    GoogleCalendar(
                        id = c.getLong(idCol),
                        accountName = c.getString(nameCol) ?: "",
                        displayName = c.getString(displayCol) ?: "",
                        isPrimary = primaryCol >= 0 && c.getInt(primaryCol) != 0,
                        accessLevel = c.getInt(accessCol),
                    ),
                )
            }
        }
        return out
    }

    /**
     * Every `com.google` calendar Kevin can write to, filtered from [allGoogleCalendars] -
     * mechanics per research doc §1.3: `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR (500)`. This
     * is the WRITE set: choosing an insert target must never land on a calendar Kevin can only read
     * (ticket 17 - a single set serving both reads and writes is exactly what hid his read-only
     * "Holidays in United States" calendar from the agenda). Empty (never an exception) under the
     * same conditions as [allGoogleCalendars].
     */
    fun writableGoogleCalendars(context: Context): List<GoogleCalendar> =
        allGoogleCalendars(context).filter {
            it.accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
        }

    /**
     * Every event occurrence across every `com.google` calendar on the device - readable or
     * writable, no access-level floor (ticket 17) - whose instance falls in `[fromMs, toMs]`,
     * ascending by start time. The `Instances` time-range URI (research doc §2) does the recurrence
     * expansion, so this stays a plain read with no date-math of its own.
     *
     * Filters instances down to [allGoogleCalendars]' ids: `Instances` itself returns rows from
     * every calendar on the device (shared, subscribed, non-Google), and only Kevin's `com.google`
     * calendars - his own and the ones he merely reads - are what tickets 13 and 17 asked this file
     * to surface. Deliberately NOT [writableGoogleCalendars]: that set is for choosing an insert
     * target, not for deciding what is worth showing him.
     */
    fun eventsInWindow(context: Context, fromMs: Long, toMs: Long): List<GoogleCalendarEvent> {
        if (!hasReadPermission(context)) return emptyList()
        val calendarIds = allGoogleCalendars(context).map { it.id }.toSet()
        if (calendarIds.isEmpty()) return emptyList()

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, fromMs)
        ContentUris.appendId(builder, toMs)
        // RRULE/RDATE and CALENDAR_ACCESS_LEVEL are both queryable straight off Instances - it
        // implements the same EventsColumns/CalendarColumns interfaces Events and Calendars do, so
        // this stays one query rather than a per-event follow-up lookup (ticket 22 points 4/5).
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.RRULE,
            CalendarContract.Instances.RDATE,
            CalendarContract.Instances.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Instances.DESCRIPTION,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val out = mutableListOf<GoogleCalendarEvent>()
        context.contentResolver.query(
            builder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            val eventIdCol = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
            val calIdCol = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
            val titleCol = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
            val beginCol = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endCol = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val allDayCol = c.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
            val rruleCol = c.getColumnIndexOrThrow(CalendarContract.Instances.RRULE)
            val rdateCol = c.getColumnIndexOrThrow(CalendarContract.Instances.RDATE)
            val accessCol = c.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ACCESS_LEVEL)
            val descCol = c.getColumnIndexOrThrow(CalendarContract.Instances.DESCRIPTION)
            val locCol = c.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
            while (c.moveToNext()) {
                val calendarId = c.getLong(calIdCol)
                if (calendarId !in calendarIds) continue
                out.add(
                    GoogleCalendarEvent(
                        eventId = c.getLong(eventIdCol),
                        calendarId = calendarId,
                        title = c.getString(titleCol) ?: "(untitled event)",
                        startMs = c.getLong(beginCol),
                        endMs = c.getLong(endCol),
                        allDay = c.getInt(allDayCol) != 0,
                        recurring = !c.getString(rruleCol).isNullOrBlank() || !c.getString(rdateCol).isNullOrBlank(),
                        calendarAccessLevel = c.getInt(accessCol),
                        description = c.getString(descCol).orEmpty(),
                        location = c.getString(locCol).orEmpty(),
                    ),
                )
            }
        }
        return out
    }

    /**
     * Inserts one non-recurring event into [calendarId] - ticket 14. `calendarId` must be one of
     * [writableGoogleCalendars]' own ids (`CAL_ACCESS_CONTRIBUTOR` or better on a `com.google`
     * account); this function does not re-check access level itself, it trusts the caller to have
     * picked from that list, the same division of labour [eventsInWindow] already has with it.
     *
     * Research doc §1.3/§1.4: `CALENDAR_ID`, `DTSTART`, `EVENT_TIMEZONE`, and `DTEND` are the four
     * columns `validateEventData` requires for a non-recurring event, all four are set below.
     *
     * **Deliberately never sets `CALLER_IS_SYNCADAPTER`.** Nothing in the API stops a caller from
     * adding it, and doing so is the one documented way to silently break upload: it skips the
     * provider's `if (!callerIsSyncAdapter) { DIRTY = 1; ... }` branch (research doc §1.1), so the
     * row never gets marked unsynced and Google's adapter never learns it exists. It would still
     * read back correctly from THIS device forever, which is exactly what makes the bug invisible.
     * Leaving the plain-app path alone is what makes the insert reach Google's servers at all - see
     * this file's class doc and ticket 02's answer.
     *
     * [allDay] follows the platform's own all-day convention, not this app's device-zone convention
     * for a local reminder's `startsAt` (`service/LiveToolbox.kt`'s `parseNoteDate` doc comment):
     * the Android Calendar Provider guide requires all-day `DTSTART`/`DTEND` to be UTC midnight of
     * the calendar date, with `EVENT_TIMEZONE = "UTC"`, regardless of the device's real zone. The
     * caller is responsible for handing this function millis already in that form for an all-day
     * event - see `service/LiveToolbox.kt`'s calendar-write dispatch for where that conversion
     * happens, kept there rather than here so this file stays a plain, untranslated write.
     *
     * Returns the new event's `_ID`, or `null` on refused permission or a provider failure (a
     * device with no writable `com.google` calendar at all, for instance). Never throws.
     */
    fun insertEvent(
        context: Context,
        calendarId: Long,
        title: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
    ): Long? {
        if (!hasWritePermission(context)) return null
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (allDay) "UTC" else java.util.TimeZone.getDefault().id,
            )
            // No CALLER_IS_SYNCADAPTER here - see this function's doc comment. Do not add it.
        }
        val uri = runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        }.getOrNull() ?: return null
        return runCatching { ContentUris.parseId(uri) }.getOrNull()
    }

    /**
     * Updates the whole SERIES in place - ticket 22 point 1/2 (a non-recurring event is its own
     * series of one, so this is also the path a plain event's edit takes). Only [title]/[startMs]/
     * [endMs]/[allDay] are touched; the id and everything else about the row stay Google's, and
     * nothing about it is copied into Room - same "nothing is stored" rule as [insertEvent].
     *
     * **Deliberately never sets `CALLER_IS_SYNCADAPTER`** - same reason as [insertEvent]: it would
     * skip the provider's `if (!callerIsSyncAdapter) { DIRTY = 1; ... }` branch and the edit would
     * sit on this device forever, reading back correctly here and never reaching Google's servers.
     *
     * Returns `false` on refused permission, a stale/unknown [eventId], or any other provider
     * failure. Never throws.
     */
    fun updateEventSeries(
        context: Context,
        eventId: Long,
        title: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
    ): Boolean {
        if (!hasWritePermission(context)) return false
        val values = ContentValues().apply {
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (allDay) "UTC" else java.util.TimeZone.getDefault().id,
            )
            // No CALLER_IS_SYNCADAPTER here - see this function's doc comment. Do not add it.
        }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = runCatching { context.contentResolver.update(uri, values, null, null) }.getOrNull() ?: return false
        return rows > 0
    }

    /**
     * Edits ONE occurrence of a recurring series - ticket 22 point 2, mechanics from
     * `research/02-calendar-api-choice.md` §5: insert an exception row at
     * `Events.CONTENT_EXCEPTION_URI` (appended with the ORIGINAL, series-parent event id) carrying
     * `ORIGINAL_INSTANCE_TIME = [originalInstanceBeginMs]` - that value must be the occurrence's own
     * `BEGIN` exactly as `Instances` returned it (`GoogleCalendarEvent.startMs`), because that is how
     * the provider identifies WHICH occurrence the exception replaces. The provider creates the
     * exception and `Instances` re-expands the series around it; no recurrence math lives here,
     * matching this file's own class doc comment.
     *
     * Never `CALLER_IS_SYNCADAPTER`, same reason as [updateEventSeries]. Returns `false` on refused
     * permission or a provider failure. Never throws.
     */
    fun updateEventOccurrence(
        context: Context,
        originalEventId: Long,
        originalInstanceBeginMs: Long,
        title: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean,
    ): Boolean {
        if (!hasWritePermission(context)) return false
        val exceptionUri = Uri.withAppendedPath(
            CalendarContract.Events.CONTENT_EXCEPTION_URI,
            originalEventId.toString(),
        )
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalInstanceBeginMs)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            put(
                CalendarContract.Events.EVENT_TIMEZONE,
                if (allDay) "UTC" else java.util.TimeZone.getDefault().id,
            )
            // No CALLER_IS_SYNCADAPTER here - see updateEventSeries's doc comment. Do not add it.
        }
        val result = runCatching { context.contentResolver.insert(exceptionUri, values) }.getOrNull()
        return result != null
    }

    /**
     * Deletes the whole SERIES (or a non-recurring event, its own series of one) - ticket 22 point
     * 1/3. Returns `false` on refused permission or a provider failure. Never throws.
     */
    fun deleteEventSeries(context: Context, eventId: Long): Boolean {
        if (!hasWritePermission(context)) return false
        // No CALLER_IS_SYNCADAPTER query parameter here either - see updateEventSeries's doc
        // comment. A plain id-appended URI has nothing to accidentally add it to, but the rule is
        // stated at every write call site in this file regardless, so it is never rediscovered by
        // accident on a future edit that DOES start building a query string.
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val rows = runCatching { context.contentResolver.delete(uri, null, null) }.getOrNull() ?: return false
        return rows > 0
    }

    /**
     * Deletes ONE occurrence - ticket 22 point 2/3, mechanics from
     * `research/02-calendar-api-choice.md` §5: an exception row whose `STATUS` is
     * `STATUS_CANCELED`, inserted the same way [updateEventOccurrence] inserts an edit exception,
     * rather than an actual row delete (the provider's exception mechanism has no separate "delete
     * this occurrence" verb - cancelling it IS the delete, and `Instances` stops expanding it).
     *
     * Never `CALLER_IS_SYNCADAPTER`, same reason as [updateEventSeries]. Returns `false` on refused
     * permission or a provider failure. Never throws.
     */
    fun deleteEventOccurrence(context: Context, originalEventId: Long, originalInstanceBeginMs: Long): Boolean {
        if (!hasWritePermission(context)) return false
        val exceptionUri = Uri.withAppendedPath(
            CalendarContract.Events.CONTENT_EXCEPTION_URI,
            originalEventId.toString(),
        )
        val values = ContentValues().apply {
            put(CalendarContract.Events.ORIGINAL_INSTANCE_TIME, originalInstanceBeginMs)
            put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CANCELED)
            // No CALLER_IS_SYNCADAPTER here - see updateEventSeries's doc comment. Do not add it.
        }
        val result = runCatching { context.contentResolver.insert(exceptionUri, values) }.getOrNull()
        return result != null
    }
}
