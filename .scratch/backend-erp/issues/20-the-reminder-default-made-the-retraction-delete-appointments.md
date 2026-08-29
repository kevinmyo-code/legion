---
type: decision
status: built
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

## RULED 2026-08-28, all four, three of them from the data rather than from taste

Kevin: "do both tickets." Delegated, so these are my calls on his authority and open to reversal.

### Q1 - what were the 88 soft-deleted rows? ANSWERED, and the retraction was RIGHT about them

Grouped from the live table:

| rows | kind | source | google id | origin_guid | dated | what they are |
|---|---|---|---|---|---|---|
| 48 | reminder | legion | no | **yes** | undated | todos deleted on the engine - **correctly retracted** |
| 40 | reminder | legion | no | no | dated | the `clock in/out for work` rows removed deliberately today |

**No action.** The 48 are the ~50 deleted todos the original 2026-08-26 incident report already described. The
retraction pass identified them correctly; the bug was never that it retracts, it was WHAT it believed it
owned.

### Q4 - are other rows carrying the `'reminder'` default without being reminders? NO, it is clean

Every active row's `kind` now matches its shape:

| rows | kind | source | google id | origin_guid | dated |
|---|---|---|---|---|---|
| 256 | appointment | google | yes | yes | dated |
| 29 | reminder | legion | no | no | undated |
| 16 | reminder | legion | no | no | dated |
| 5 | reminder | legion | no | yes | undated |
| 3 | reminder | legion | no | yes | dated |

Zero reminders carry a `google_event_id`; zero appointments lack one. The contradiction that exposed the
whole incident no longer exists anywhere in the table.

**One thing noticed and NOT acted on:** 29 active undated reminders sit server-side, while the migration
screen's own copy says "undated note items are never uploaded - they stay on the engine." Either the copy
is stale or something uploaded them. Not urgent, not this ticket, but it should not be forgotten - it is
the same class of thing as the `kind` default: a stated invariant that the data does not hold to.

### Q3 - should the unique indexes become partial on `deleted_at is null`? NO. Leave both.

The premise changed underneath this question. `SupabaseEventsBackend.uploadMigratedEvent` now checks BOTH
`origin_guid` and `google_event_id`, so a soft-deleted row is found by the guard and reported not-new
instead of being insert-attempted and rejected by the index. The index never bites any more.

And a soft-deleted row SHOULD keep reserving its key. That is what makes a retraction stick: without it,
the very next reconcile would re-upload every row it had just retracted, and the retraction would be a
no-op that costs two round trips. Making the index partial would have bought a symptom fix and sold the
mechanism.

### Q2 - should the retraction run in its current form? NO, and this is the one that owes code

It removed 213 rows in one pass and reported it as one line inside a success message. Nothing stated the
count before acting, nothing bounded it, and the only reason the damage was found is that the NEXT run
failed loudly on an unrelated index.

**The rule: a retraction is a deletion, and a routine sync may not perform an unbounded one as a side
effect.**

- The pass computes its retraction set FIRST and always **names the count in words**, retracted or not.
- Below a small bound it proceeds, as today. The common case is one or two rows and asking about those
  would train the answer "yes" into a reflex, which is worse than not asking.
- **Above the bound it retracts NOTHING**, reports what it would have removed and why, and says plainly
  that it needs to be run again to confirm. The second run is the consent.
- The bound is on the retraction set relative to what is actually there, not a fixed number - 213 of 354
  should stop it; 213 of 50,000 is a different judgement and this app will never see it.

This is CLAUDE.md section 7's outcome-verb rule pointed at the other end: that rule stops the app claiming
an action it did not take, and this one stops it taking an action it did not announce.

**What this deliberately does NOT do:** add a confirmation dialog. The migration screen has no dialogs by
design (its own doc comment: "a clear label is enough for a maintenance screen the user navigated to
deliberately"), and a modal that appears only on the dangerous path is a modal people learn to dismiss.
Refusing and requiring a second deliberate tap is the same consent with none of the muscle memory.
