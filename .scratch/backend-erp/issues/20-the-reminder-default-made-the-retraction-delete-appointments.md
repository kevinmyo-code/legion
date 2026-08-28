---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The `kind` default was safe for one consumer and dangerous for the next

**Found 2026-08-28 on the live project, while trying to refill the phone after a routine data
cleanup. 213 of Kevin's Google Calendar appointments were soft-deleted server-side by a pass that
believed it owned them.**

## What happened, in order

1. `20260827000200_events_kind.sql` added `public.events.kind` as
   `text not null default 'reminder'`. Its header argues the default deliberately: *"a row whose
   origin is unknown is treated as something the app owns, which is the conservative direction. An
   appointment wrongly treated as a reminder is visible and merely annoying; a reminder wrongly
   treated as an appointment silently never fires at all."*
2. **That reasoning is correct for `AlarmScheduler`**, the consumer it was written about.
3. Every row that predated the column took the default. **Google Calendar imports are among them**,
   so 213 appointments have been sitting server-side labelled `kind = 'reminder'` ever since - a
   contradiction visible in the data, since a reminder has no `google_event_id`.
4. `EventsReconcile`'s retraction pass (ticket 11 ruling 2) soft-deletes a server row whose
   `origin_guid` names a trashed-or-absent engine record. It reads `kind = 'reminder'` on those 213
   rows, correctly concludes it owns them, finds no engine record - **they were never engine
   records, they came from Google** - and retracts all of them. The screen reported "Removed 213
   rows on the server whose device original was deleted or is gone."
5. The next run then cannot re-upload them: `events_google_event_id_idx` is partial on
   `google_event_id is not null` but **not** on `deleted_at is null`, so a soft-deleted row still
   reserves its natural key. `Notes + Dates` now fails outright with a duplicate-key violation.

## The lesson, and it is not "the default was wrong"

**The default was chosen for one consumer and inherited by another with the opposite failure
direction.** For `AlarmScheduler`, "the app owns this row" means "fire an alarm for it" - annoying
when wrong. For the retraction pass, the same sentence means **"delete it if the engine doesn't have
it"** - destructive when wrong. The second consumer did not exist when the default was chosen, and
nothing forced a re-reading of the choice when it arrived.

A default that encodes "assume we own it" is safe only while every owner does something harmless
with ownership. That is a property of the whole set of consumers, not of the column, so it cannot be
settled once at the schema and left alone.

## State of the data

Nothing is lost. At the time of writing: 355 events, 54 active, 301 soft-deleted, of which 213 carry
a `google_event_id` and 40 are the deliberately-removed `clock in/out for work` reminders. The phone
still holds 109 active appointments locally, so the device copy is intact.

The restore is `deleted_at = null, kind = 'appointment'` for every soft-deleted row with a
`google_event_id` - which both un-deletes them and stops the retraction from taking them again.

## Open, and each needs a ruling

1. **~48 soft-deleted reminders are unaccounted for** (301 minus 213 Google minus 40 clock-in). What
   are they, and were they retracted correctly? Nobody has looked.
2. **Should the retraction pass be allowed to run at all in its current form?** It removed 213 rows
   in one pass on live data and reported it as a routine line in a success message. A retraction of
   that size deserves at minimum a stated count before it acts, and arguably a confirmation.
3. **Should `events_google_event_id_idx` become partial on `deleted_at is null`?** A soft-deleted row
   reserving a natural key is what makes this unrecoverable-in-place rather than merely wrong. The
   same question applies to `events_origin_guid_idx`, and the two answers may differ: origin_guid is
   deliberately the "already uploaded" guard, so a retracted row arguably SHOULD still block
   re-upload.
4. **Are there other rows still carrying the `'reminder'` default that are not reminders?** The
   Google imports were found by having a `google_event_id`. A row with neither that nor an engine
   ancestor would be invisible to the same query.

**Do not re-run `Notes + Dates` until 2 is ruled.** The pass will retract again on whatever it still
believes it owns.

## RESTORED 2026-08-28, and the count that mattered proved the diagnosis

`update public.events set deleted_at = null, kind = 'appointment' where deleted_at is not null and
google_event_id is not null returning id` → **213 rows**. Verified from the table afterwards, not
from the panel:

| | before | after |
|---|---|---|
| active | 54 | **267** |
| soft-deleted | 301 | **88** |
| soft-deleted carrying a `google_event_id` | 213 | **0** |
| `kind = 'appointment'` | 1 | **214** |
| `kind = 'reminder'` carrying a `google_event_id` | 213 | **0** |
| active with `missed_at` | 57 | **17** |

The last two rows are the diagnosis confirming itself. **Zero reminders now carry a Google event id**
- the contradiction that gave the whole thing away is gone - and the missed count fell to exactly the
17 genuine overdue reminders, with the 40 deliberately-removed `clock in/out for work` rows out.

## A process finding worth more than the data fix

**Three separate statements today were reported as run and had not run.** Two updates and one
migration. The cause was the same each time and it was not the database: the SQL was being copied
out of chat together with the prose around it, and Postgres parses an entire batch before executing
any of it, so one English sentence silently aborts the whole thing. Twice it surfaced as
`syntax error at or near "Then"`; once it looked like a successful no-op.

It cost roughly an hour and produced two wrong diagnoses along the way - a suspected RLS block and a
suspected resurrection bug - both of which were investigated against live data before the real cause
appeared.

**What actually fixed it: `returning id`.** A statement that reports what it touched cannot be
mistaken for one that touched nothing. That is the same rule this project keeps re-learning in
different costumes today - a stale `SCHEMA_VERSION` that disabled restores, a rendered line claiming
"already all on the server", a migration verified by a success panel - and it now has a fourth
instance and a cheap remedy: **when a statement matters, make it return its own effect.**

## Still open, unchanged by the restore

Questions 1, 2 and 4 above stand. Question 3 is now less urgent for `google_event_id` (no
soft-deleted row holds one) but the design question is untouched.

**The specific cause of the 213-row retraction is fixed** - those rows are `appointment` now and the
pass does not own them. **The general question in item 2 is not**: a retraction that removes hundreds
of rows and reports it as one line in a success message is still the shape of the problem, and the
next mislabelled cohort would go the same way.
