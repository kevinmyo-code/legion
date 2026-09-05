---
map: two-clients
ticket: "06"
title: "Google Calendar rows are a snapshot from 2026-09-01, not a feed"
type: decision
status: open
status-detail: "Django parked 2026-09-05, so option 1 has no host. Decision still open: stay frozen and prune, or another path."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Google Calendar rows are a snapshot from 2026-09-01, not a feed

Found 2026-09-05. "Labor Day" rendered on Sunday Sep 6; the row was stored `all_day = false` at UTC
midnight, so every reader treated it as 7 PM the evening before. 18 Google rows had the same shape
and were corrected by SQL. The importer fix that was briefed alongside it turned out to have nothing
to patch: **`calendar/CalendarImportController.kt` and `CalendarProvider.kt` were retired on
2026-09-01** (`222e31e`, "Cut Google entirely; appointments become tickable"), and
`NoCalendarContractTest` fails the build if `CalendarContract` reappears.

So the 151 `source = google` rows on the server - 96 class meetings, birthdays, holidays, the
appointments - are **frozen as of Sep 1**. Nothing added to Google Calendar since then reaches
LEGION. Nothing changed there is reflected. The class meetings will still show on Thanksgiving week
and on the Dec 10 no-class day because the recurrence was expanded once and never re-read.

## The decision

Two honest options, and the third is not one:

1. **Google Calendar comes back as a Django poll** (ADR 0043: what must run while the phone is
   asleep). Google Calendar API with Kevin's own OAuth client, BYO like the Gemini key, polling on a
   schedule, upserting `source = google` rows keyed on `google_event_id` (the unique index already
   exists), setting `all_day` from `start.date` vs `start.dateTime`. Deleted-in-Google events get a
   tombstone. The phone stays a reader.
2. **Stay frozen, and say so.** Delete the recurring class-meeting rows past the point they are
   trusted, keep one-offs, and let the syllabus rows and Canvas carry the schedule. Cheaper, and the
   calendar stops lying about Thanksgiving - but birthdays and appointments then live only in Google.
3. ~~Re-introduce the on-phone `CalendarContract` importer.~~ Not an option: the 2026-09-01 cutover
   retired it deliberately and the build enforces that.

Whichever is chosen, **the `all_day` rule is written down now**: a date-only Google event is stored
`all_day = true` with `starts_at` at UTC midnight of the date, matching
`activeByKindInLocalWindow`'s convention. Any future ingestion that sets the flag false for a
date-only event reintroduces the Sep 6 Labor Day.
