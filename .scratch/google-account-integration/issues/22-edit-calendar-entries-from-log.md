# BUILD: edit and delete calendar entries from the Log stream

Type: task
Status: resolved
Blocked by: -

## Question

Kevin, 2026-08-13: "the calendar entries in Log - i want to be able to manually edit those."
Scope settled the same day: **edit everything including recurring, with a this-one-or-all prompt;
delete included, behind a confirm.**

**This reverses a rule, deliberately, and it is Kevin's call.** The ticket 13 follow-up made Google
rows read-only in `InboxRow` - no tick, no edit, no remove. That came from
[ticket 04](04-what-happens-to-local-timed-items.md)'s "Google owns appointments, LEGION owns
reminders, nothing is ever written to both".

**Ticket 04 is NOT violated by this.** That rule forbids the same appointment existing in two
stores. Editing a Google event **in place** keeps it in exactly one store - Google's - and LEGION
copies nothing. What changes is only that LEGION is now a writer to Google's copy, which it already
is for creation ([ticket 14](14-calendar-write.md)). Reading the rule as "never write" would have
forbidden creation too.

## The work

1. **Edit a Google event from the Log stream**: title and time. Reuse `CalendarProvider`; add update
   and delete alongside the existing `insertEvent`. **Never `CALLER_IS_SYNCADAPTER`** - the same rule
   and the same reason as insert.
2. **Recurring events get a "this one or all of them" prompt.** Mechanics, from
   `research/02-calendar-api-choice.md`: editing ONE occurrence is an exception row via
   `Events.CONTENT_EXCEPTION_URI` with `ORIGINAL_INSTANCE_TIME` set to that instance's `BEGIN`;
   editing the SERIES is an update to the parent `Events` row. Deleting one occurrence is an
   exception with `STATUS_CANCELED`; deleting the series deletes the parent.
   A non-recurring event gets **no prompt** - there is nothing to disambiguate.
3. **Delete is behind a confirm**, because it propagates to Google within seconds (proved on-device
   2026-08-13, ticket 14) and is not undoable from here.
4. **A read-only calendar's events must NOT be editable.** [Ticket 17](17-read-all-calendars.md)
   deliberately widened READING to every `com.google` calendar, so Kevin's "Holidays in United
   States" (access level 200) now renders in the stream. **Those rows must stay read-only and say
   why** - "this calendar is read-only" - rather than offering an edit that fails. The row needs the
   event's calendar access level to decide, so carry it through with the event.
5. **The row must carry what an edit needs**: event id, the instance's `BEGIN`, whether it recurs,
   and its calendar's access level. `InboxRowView` currently gives Google rows a synthetic negative
   id (`-(eventId + 1)`) to avoid colliding with Room ids - that trick stays for identity, but it is
   not enough to edit with.
6. **Pure logic in a resolver, unit-tested**, matching this repo's convention: which actions a row
   offers (editable / read-only-calendar / recurring), and what the prompt asks. The provider calls
   themselves are Android-bound and stay out of it.
7. **Local items are untouched.** They keep their existing edit and REMOVE path through
   `NotesController`. Nothing here routes a Google event into `NotesController`, ever.

## Verification

**On the device, and this one cannot be checked any other way:**

- Edit a one-off event's title. Confirm it changes in the Google Calendar app.
- Edit ONE occurrence of a recurring birthday. Confirm that year changed and the others did not.
- Edit the SERIES. Confirm every occurrence changed.
- Delete one occurrence, and separately a whole series, each via the confirm.
- Open a US holiday row and confirm it offers no edit and says why.
- Confirm a local item still ticks, edits and removes exactly as before.

## Answer

**Built 2026-08-13. 759 tests green, verified by the orchestrator directly. Partly verified
on-device - and the on-device pass found a crash the whole suite could not.**

- `CalendarProvider` gains `updateEventSeries`, `updateEventOccurrence`, `deleteEventSeries`,
  `deleteEventOccurrence`. Occurrence edits and occurrence deletes both go through
  `Events.CONTENT_EXCEPTION_URI` with `ORIGINAL_INSTANCE_TIME` (a delete is that plus
  `STATUS_CANCELED`). **`CALLER_IS_SYNCADAPTER` appears nowhere as an argument** - grep-confirmed,
  comments only.
- `GoogleCalendarEvent` carries `recurring` and `calendarAccessLevel`, both read from the SAME
  `Instances` query - no second round trip.
- New Android-free `CalendarEditResolver`: `rowAction` -> `READ_ONLY` / `EDITABLE` /
  `EDITABLE_RECURRING`, and `scopePrompt`, which returns null for a non-recurring edit so no
  pointless dialog ever appears.

### Verified on-device

- **The `Instances` column claim was the ticket's one `reasoned` platform fact, and it holds.**
  Queried directly: birthdays return `rrule=FREQ=YEARLY;WKST=MO, calendar_access_level=700`;
  US holidays return `rrule=NULL, calendar_access_level=200`. Exactly the inputs the resolver needs.
- **The scope prompt renders**: "Recurring event - Just this one, or the whole series?" with
  ALL OF THEM / THIS ONE / CANCEL.
- Google rows show `CAL` + DELETE; the local row keeps its checkbox and REMOVE, untouched.

### The crash this found, and why the suite never could

Creating a throwaway `FREQ=DAILY;COUNT=3` event to test the prompt without touching Kevin's real
data **killed the app on entry to Notes**:

```
java.lang.IllegalArgumentException: Key "-180" was already used.
```

A recurring event expands to **one row per occurrence**, and every one of them carried the same
synthetic `-(eventId + 1)` id, so `LazyColumn` got three identical keys and threw. **Kevin's own
recurring events are all YEARLY, so exactly one occurrence falls inside the 90-day window and the
collision never fired** - the bug was latent, not absent, and the first weekly or daily event he
created would have taken the screen down.

Fixed by keying on id **and** occurrence start. A LOCAL row has no occurrence time and keeps its
plain Room id. Re-verified: three occurrences render, count 24 -> 27, no crash.

**No unit test could have caught this** - the key collision only exists inside `LazyColumn`'s
measure pass. It needed a recurring event with more than one occurrence in the window, on a device.

### Still deferred, Kevin's

Editing a real one-off title, editing ONE occurrence versus the SERIES and confirming only the right
ones changed, deleting an occurrence and a series, and opening a US holiday to confirm it offers no
edit and says why. **The write paths themselves are unexercised** - only the read, the resolver and
the prompt have been seen. The provider-upload mechanism they depend on IS proven (ticket 14's
spike, re-confirmed today: this ticket's own test event deleted and the tombstone cleared upstream).
