# BUILD: read Google Calendar onto the agenda

Type: task
Status: resolved
Blocked by: 12

## Answer

**Built 2026-08-13. Compile and full suite verified by the orchestrator directly, not relayed:
671 tests, 0 failures, 0 errors, `cleanTestDebugUnitTest` so nothing was `UP-TO-DATE`.**

- `calendar/CalendarProvider.kt` - read-only `CalendarContract` wrapper. `writableGoogleCalendars`
  (`ACCOUNT_TYPE = com.google`, `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR`) and
  `eventsInWindow` over the `Instances` time-range URI. **Nothing stored** - no entity, no DAO, no
  cache, no Room change, as ticket 04 point 5 requires. Degrades to an empty list rather than
  throwing when permission is refused or no Google account exists.
- `ui/notes/CalendarAgendaResolver.kt` - pure, Android-free: `mergeAgenda` and
  `buildAgendaCalendarNotice`, the latter keeping permission-state wording separate from
  "list is empty" so a denied permission can never render as "you have nothing on".
- `AgendaEntry` gains `source: AgendaSource = LOCAL`; Google rows carry a `CAL` tag.
- `READ_CALENDAR` in the manifest, requested in context from the agenda, never at startup.
  No `WRITE_CALENDAR` - that is ticket 14.
- 9 new tests, including the denied-permission-with-zero-entries case.

### Scoping gap found by the executing agent, resolved by Kevin

The agent merged Google events into **`ui/TodayScreen.kt`'s AGENDA pane only** and left
`ui/notes/InboxScreen.kt` untouched, on the reasoning that `Instances` needs a time window and
`InboxScreen` has no window concept. It surfaced this rather than burying it - correct, and the
reasoning was sound, but it was half the feature: `InboxScreen` is "one stream, every item, each
with its own due date" (Kevin, 2026-08-11) and is where items are actually managed. An appointment
visible on Today and absent from Notes is the inconsistency.

**Kevin, 2026-08-13: `InboxScreen` carries Google events too, over a 90-day forward window, nothing
in the past.** Far enough for anything worth planning around, short enough that a yearly recurring
series does not flood the stream. Built as a follow-up in the same ticket.

### VERIFIED ON DEVICE 2026-08-13 - the binding render check is DONE

Installed over the top on the OnePlus CPH2471 (APK sha256 confirmed byte-identical on device, per
`memory/`'s install-by-hash rule) and observed directly:

- **Before granting**: the AGENDA pane read "Nothing due today" **plus** "Calendar not linked -
  grant access to see Google events here too" with a GRANT action. **The never-an-empty-day rule
  works**: it says why, rather than implying the day is clear.
- **After granting** (`READ_CALENDAR` and `WRITE_CALENDAR` both `granted=true, USER_SET`): Today
  read "NOTHING SCHEDULED", and a direct provider query confirmed that is **true** - zero instances
  in the next 24h. Not a false empty.
- **The Notes stream rendered four real Google events**, each tagged `CAL`, chronologically
  interleaved and correct: [four real calendar entries verified, names and dates redacted from this
  public record]. **None of them tickable, none removable** - no checkbox and no REMOVE, while
  local items kept both. Ticket 04's read-only rule holds in the UI.

### A real defect the render found, exactly as this class of check is supposed to

**Kevin's US Holidays calendar is invisible.** The device carries four `com.google` calendars:
Family (700), a company-holidays calendar (700), Personal (700), and **Holidays in United States
(200 = read-only)**. `writableGoogleCalendars` filters on `CALENDAR_ACCESS_LEVEL >= 500`, so the
holidays calendar is dropped - Halloween, Veterans Day, Election Day and Daylight Saving all exist
in the 90-day window and none of them render.

**The filter is right for writing and wrong for reading.** Choosing a target calendar for an insert
must require write access; listing events to display must not. This was carried as
`reasoned, unverified` from the build and is now a confirmed, user-visible bug. **Fix: split the
query - read from all `com.google` calendars, write only to those at CONTRIBUTOR or above.**
Follow-up ticket, not a silent patch.

### Still deferred

- `reasoned`, unverified: that the permission-refusal path re-renders the prompt rather than
  silently retrying. The grant was accepted first time on device, so the refusal path was never
  walked.

## Question

Nothing to decide. Graduated 2026-08-13 from [ticket 02](02-calendar-api-choice.md),
[ticket 04](04-what-happens-to-local-timed-items.md) and [ticket 08](08-deck-surface.md).

1. **A provider layer over `CalendarContract`**, read side: list writable `com.google` calendars
   (`ACCOUNT_TYPE = "com.google"`, `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR`), and query the
   `Instances` time-range URI for a window. **Nothing is stored** - no entity, no DAO, no cache, no
   Room change (ticket 04 point 5/6).
2. **`READ_CALENDAR` runtime permission**, requested in context from the agenda, with the
   never-an-empty-day rule from ticket 08 point 5 when it is not granted.
3. **Merge into the existing agenda**: local timed `ListItem`s (skips subtracted during expansion, as
   `notes-lists-calendar` ticket 08 decided) plus `Instances` over the same window, sorted by start.
   The provider expands a series for us, so no recurrence code is written on the Google side.
4. **Source is visible on every row** - a Google event reads as a calendar event, in words as well
   as colour. Amber for events (data); red stays reserved for a LEGION reminder that needs Kevin.
5. **Today's summary** picks up Google events the same way it picks up local reminders.
6. **No notifications for Google events.** Ticket 04 point 3. Google Calendar already does this.

## Verification

- **Render the agenda with both sources present before anything is built on top of it**: a day
  holding a Google event and an overdue local reminder in the same window. Binding, per CLAUDE.md §8
  (L11) - ticket 08 names this explicitly and the colour call cannot be checked by reading code.
- On the device with Kevin's real calendar, not a fixture.
- Deny `READ_CALENDAR` and confirm the agenda says so rather than rendering an empty day.
