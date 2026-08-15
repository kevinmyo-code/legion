# BUILD: read from every Google calendar, write only to writable ones

Type: task
Status: open
Blocked by: -

## Question

Nothing to decide. Found on-device 2026-08-13 while running
[ticket 13](13-calendar-read.md)'s binding render check, which is precisely the class of bug that
check exists to catch.

**The defect.** `CalendarProvider.writableGoogleCalendars` filters on
`CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR (500)`, and **both** the read path and the write
path use it. Kevin's device carries four `com.google` calendars:

| Calendar | Access | Currently visible |
|---|---|---|
| Family | 700 | yes |
| Company Holidays | 700 | yes |
| Personal | 700 | yes |
| **Holidays in United States** | **200 (read-only)** | **no** |

Halloween, Veterans Day, Election Day and Daylight Saving all sit in the 90-day window and none of
them render, on either surface. A subscribed or shared calendar Kevin can only read is exactly the
kind he would still want to *see*.

**The fix.** Split the query by purpose:

1. **Reading** - `eventsInWindow` covers **every** `com.google` calendar on the device, whatever its
   access level. No floor.
2. **Writing** - choosing a target calendar for an insert keeps the `>= CAL_ACCESS_CONTRIBUTOR`
   floor. Inserting into a calendar Kevin cannot write is a guaranteed failure.
3. Rename accordingly so the distinction is legible - a single `writableGoogleCalendars` serving
   both callers is what caused this.

## Verification

- On the device, confirm the US holidays appear in the Notes stream's 90-day window and on Today
  when one falls on the day.
- Confirm an insert still targets a writable calendar and does not attempt the read-only one.
- Unit-test the split if any pure logic falls out of it; the provider query itself is Android-bound.
