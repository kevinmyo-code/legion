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
