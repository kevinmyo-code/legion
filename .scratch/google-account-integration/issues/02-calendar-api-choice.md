---
map: google-account-integration
ticket: 02
title: "CalendarContract or the Calendar REST API?"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# CalendarContract or the Calendar REST API?

## Question

The biggest technical fork on the map. Two ways to reach the same Google Calendar:

- **`CalendarContract`** - Android's on-device calendar provider. No OAuth, no scope, no console;
  a `READ_CALENDAR`/`WRITE_CALENDAR` runtime permission and whatever the Google Calendar app has
  already synced. Offline for free.
- **Calendar REST API** - a `sensitive` OAuth scope through the same client that now works for
  Drive. Full fidelity, independent of what the phone has synced, but its own network, quota,
  caching and offline story.

Judge both against Kevin's four wants (map settled decision 6), not in the abstract:

1. **Read into Today.** Can each one list events across a window cheaply enough to render a deck
   panel? What does it cost per read?
2. **Alfred writes events.** Can `CalendarContract` insert into a **Google account** calendar such
   that the event syncs up to Google, or does it only reliably write a local-only calendar? This is
   the make-or-break question for the provider route - answer it specifically, with the account-type
   and `CALENDAR_ID`/`ACCOUNT_TYPE` mechanics.
3. **Two-way visible.** Does an edit made in the Google Calendar app show up through each route, and
   how soon? What is the change-notification mechanism (`ContentObserver` vs REST sync tokens /
   push)?
4. **Conflict awareness.** Cheapest way to ask "is Kevin busy at T" through each.

Also settle, because settled decision 2 hangs on it:
- **Recurrence fidelity.** Does `CalendarContract` expose RRULE, EXDATE, and per-instance overrides,
  and does its `Instances` table expand a series for you? Same for REST.
- **Deleting or editing one occurrence of a series** through each route.
- **Permission and consent cost to the user** for each, side by side.

Deliver a recommendation, not a survey. Tag every claim as documented / inferred / would-need-a-spike.

Findings go to `.scratch/google-account-integration/research/02-calendar-api-choice.md`.

## Answer

**`CalendarContract`. No Calendar OAuth scope at all.**
Full findings and citations: [research/02-calendar-api-choice.md](../research/02-calendar-api-choice.md).
Resolved 2026-08-13 from a research agent's report; tags are the agent's, carried forward unchanged
and NOT independently re-verified by the orchestrator.

1. **Q2, the make-or-break, is answered mechanically and the folklore is wrong** (`documented` from
   AOSP source). `CalendarProvider2.insertInTransactionInner` does
   `if (!callerIsSyncAdapter) { values.put(Events.DIRTY, 1); addMutator(...) }` then
   `notifyChange(CalendarContract.CONTENT_URI, null, syncToNetwork)` with `syncToNetwork` true
   **exactly when the caller is not a sync adapter**. An ordinary app's insert is the intended
   upload path - the same one the AOSP Calendar app uses.
   - The "apps only get local calendars" belief comes from a real restriction on the **`Calendars`**
     table (an app may write only `NAME`, `CALENDAR_DISPLAY_NAME`, `VISIBLE`, `SYNC_EVENTS`). It does
     not constrain `Events` rows.
   - `ACCOUNT_TYPE_LOCAL` is an opt-in "do not sync this" special case, not a fallback.
   - Mechanics: pick a `Calendars` row with `ACCOUNT_TYPE = "com.google"`, the right `ACCOUNT_NAME`,
     and `CALENDAR_ACCESS_LEVEL >= CAL_ACCESS_CONTRIBUTOR (500)`; insert with `CALENDAR_ID`,
     `DTSTART`, `EVENT_TIMEZONE`, plus `DTEND` or `DURATION`+`RRULE`.
   - **Never append `CALLER_IS_SYNCADAPTER`.** Nothing stops you, and it is the single way to
     silently break upload.
   - Residual (`needs-a-spike`): Google's closed-source sync adapter doing the final upload is
     `inferred`. A 20-minute on-device spike settles it and **blocks the build**, not this decision.
2. **REST cannot do push on a phone** (`documented`). `events.watch` requires an HTTPS webhook with a
   valid certificate. LEGION has no backend and CLAUDE.md §7 forbids one, so REST's two-way story
   degrades to a `syncToken` poll loop. That is a hard requirement colliding, not a preference.
3. **Reads and conflict-awareness are free on the provider** (`documented`). The `Instances`
   time-range URI is local, offline, and expands a series for you - it is *exactly* the agenda query
   `notes-lists-calendar` ticket 08 already chose. REST is a network round trip per panel render.
4. **Recurrence fidelity is a tie** (`documented`). The provider carries `RRULE`, `RDATE`, `EXRULE`,
   `EXDATE`, real exception rows, and `Events.CONTENT_EXCEPTION_URI` for editing or deleting one
   occurrence. Map settled decision 2 holds on either route.
5. **Consent cost**: a `READ_CALENDAR`/`WRITE_CALENDAR` runtime permission pair, versus an OAuth
   scope plus its own offline handling. The provider is cheaper on both.

### Two corrections to this ticket's own framing

- **Calendar scopes are `sensitive`, not `restricted`.** The map implied otherwise. REST's marginal
  OAuth cost was therefore smaller than charted - it still loses, on push and offline, not on tier.
  This also settles the item ticket 01 could only infer.
- **The agent argued the 7-day Testing expiry probably does not bite LEGION**, because `DriveAuth`
  uses the GMS Authorization API and stores no refresh token. **This directly contradicts
  [ticket 01](01-testing-status-token-lifetime.md)**, whose agent found the expiry documented at the
  **grant** layer, where storing no refresh token is irrelevant. See that ticket's answer for the
  disagreement, which is unresolved and settled empirically by
  [ticket 11](11-publish-the-consent-screen-now.md), not by argument.
  This agent also mis-dated the evidence: it read the 2026-08-03 device run as a successful connect,
  when `DriveAuth`'s own doc comment records that run **failing** with `DEVELOPER_ERROR`. There is no
  free 10-day-old data point. **Discount this correction; do not act on it.**

### What this unblocks

Tickets 04, 08 and 10 lose their blocker. Ticket 10 (offline) collapses substantially, as ticket 02
predicted it might: the provider is already local, so caching is largely a non-question. Ticket 09
no longer needs a Calendar scope added to the client - only Gmail.
