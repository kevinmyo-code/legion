---
type: decision
status: resolved
blocked_by: []
map: backend-erp
---

# An undated note has no shape in the merged events table

**Filed 2026-08-26, on building the Notes+Dates cutover. The data layer is built and tested; the
CONTROLLER REWIRE IS DELIBERATELY NOT DONE, and this is why.**

## The problem, in one line

`public.events` declares `starts_at timestamptz NOT NULL`. **A dateless checklist item cannot be
represented as a row in it at all.**

## Why this was not obvious

The migration's own header says the merge drops nothing: "21 Item fields and 7 Event fields become
one shape... Nothing is dropped." That is true field-by-field - every COLUMN has a home. What it
does not say is that the merged table imposes a NOT NULL pair (`title`, `starts_at`) that the Item
shape never required. **The fields all survived; a whole class of ROW did not.**

Ticket 01 ruling 4 said todos become Dates events, and that is right for a todo with a due date. The
ruling did not distinguish "a todo due Thursday" from "milk", and the schema quietly did.

## MEASURED 2026-08-26, against Kevin's real restored database

Counted directly off the phone's engine records (592 total, restored from the Drive backup), so
these are actual figures, not an estimate:

| Record type | Total | Dated | **Undated** |
|---|---|---|---|
| Notes `Item` | 56 | 3 | **53 (95%)** |
| Dates `Event` | 269 | 269 | 0 |

**Under `starts_at NOT NULL`, the Notes+Dates reconcile uploads 272 rows and silently leaves behind
53 - ninety-five per cent of everything Kevin has actually authored in this aspect.**

The 269 Events being uniformly dated is not the reassurance it looks like. `Dates.Event` has no live
writer except the Google Calendar importer, which ticket 01 ruling 5 is REMOVING, so that column is
full precisely because a soon-to-be-deleted integration filled it. Strip the import and the aspect
is 56 rows, 53 of them unrepresentable.

**This turns option 1 from a preference into the only defensible answer.** A merged table that
cannot hold 95% of the live data is not a merge; the "one required pair" design intent was written
against the Event shape and never tested against the Item shape it was merging with.

## How big the affected class is

Not an edge case. Traced while building:

- `Dates.Event` has **no live production writer** except the Google Calendar importer, which ticket
  01 ruling 5 is removing. Every dated item a person actually creates today by voice or by hand is a
  Notes `Item`.
- Most `NotesController.addItem` calls create genuinely dateless entries.

So the merged table's required column is required by almost nothing that writes today.

## What was built anyway, and what was not

**Built and tested (2629 tests, 0 failures):** the v38 Room replica (`events_replica`,
`event_skips_replica`, migration SQL verified byte-identical to Room's own generated schema),
`EventsBackend`, `SupabaseEventsBackend`, and `EventsReconcile` carrying the full field-by-field
merge with a test asserting every mapped field on both shapes.

**Not built:** the controller rewire. `NotesController` (503 lines) is the single live write surface
for todos, reminders, recurrence, place triggers and alarms. Rewiring it without resolving this
would mean deciding per-item which store a row lives in - engine when dateless, server once dated -
which is a modelling decision, not a mechanical port. `ListItem.id` is also an `AlarmManager`
`PendingIntent` request code and a foreign key from `WorkoutSetLog`/`GoalChecklistSync`, so getting
it wrong silently breaks real alarms on the phone. `NotesController`, `DatesAgenda`,
`ReminderController` and `AlarmScheduler` are untouched: zero regression, and also no live sync for
this aspect yet.

**The reconcile's interim behaviour:** an undated Item is reported in `Report.skippedUndated` and
NOT uploaded, and `isClean` stays false while any exist. It does not invent a date. That was the
right call and it is worth stating why: ruling 2's inferred-tomorrow default is a READ-SIDE
rendering rule, and CLAUDE.md section 4 rule 5 forbids storing something the source never stated as
though it were stated. A guessed due date would be exactly that.

## RULED 2026-08-26 (Kevin): option 1. Applied.

`supabase/migrations/20260826000400_events_starts_at_nullable.sql`, applied to the live project and
verified: `events.starts_at` is nullable, `title` is still NOT NULL.

**What it does not license.** An undated item is stored as NULL, never as a guessed date. Ruling 2's
inferred-tomorrow is a READ-SIDE rendering rule; storing it would assert something the user never
said (section 4 rule 5). And `starts_at` is the agenda's sort key, so **every ordering query over
this table now needs an explicit null policy** - `NULLS LAST` is the intent, decided at each query
site rather than baked into the column.

**Still to build, and it is the part that was blocked:** `EventsReconcile` must stop skipping
undated items and upload them with a null start, and the `NotesController` rewire can now proceed -
the per-item "which store does this live in" fork that made it undecidable is gone, because both
dated and undated items have one home. Deliberately NOT started while another agent is mid-flight
in the pantry files; a Room replica change for the nullable start would collide with its migration.

## The options

1. **`starts_at` becomes NULLABLE.** Smallest change. An undated item is a row with a null start,
   the agenda already knows how to render one, and ruling 4 holds unchanged. Cost: `starts_at` is
   the agenda's sort key, so every ordering query needs a null policy, and the "one required pair"
   design intent is gone.
2. **Undated items stay engine-local**, and a row is promoted to `events` when it gains a date.
   Keeps the server table's shape honest. Cost: two stores for one user-visible list, and the
   promotion is a move rather than an update - the failure mode is an item that exists in both or
   neither.
3. **A sentinel date.** Rejected on sight, recorded so nobody proposes it again: it stores a fact
   the user never stated, which is the thing section 4 rule 5 exists to forbid.

**Recommendation: option 1, and the measurement above makes it close to forced.** It is the only one that keeps a single list in a single store, and the
cost is a null-ordering policy in a handful of queries rather than a distributed-state problem. If
taken, the migration's "one required pair" comment must be corrected in the same commit, because it
would then be documenting an intent the schema no longer has.
