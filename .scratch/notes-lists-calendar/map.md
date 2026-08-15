# Map: Notes, lists and a local calendar

Label: `wayfinder:map`
Effort: `.scratch/notes-lists-calendar/`
Charted: 2026-08-07

## Destination

**A decided design for a single notes, lists and calendar domain in LEGION: one model that absorbs
car tasks and place reminders, holds checklists, notes and timed events including recurring ones,
created and edited by voice or by hand, with local alarms and no Google dependency.**

Reached when: the entity model and the `car_tasks` migration are decided, recurrence is decided, the
voice grammar for editing an existing item is decided, the alarm mechanism is decided, navigation is
decided, and cross-phone sync is decided. **Not reached by building any of it** - this map produces
decisions, and the builds are separate efforts after it.

**Amended 2026-08-07, mid-effort (Kevin): photo ingestion is OUT.** It was the origin of the whole
idea - a paper camping checklist - and it is now cut. See Out of scope. This retires charting
decision 3 entirely, which is a net simplification: that decision was the only narrowing of
CLAUDE.md §4 on this map, and the only thing here carrying precedent risk.

## Notes

**Domain:** LEGION, Android phone app (Kotlin, Compose, Room **v9** as of 2026-08-07),
`com.kevin.legion`. Branch `feat/ledger-ingestion`. Read `CLAUDE.md` for rules and `memory/MEMORY.md`
for state before deciding anything. Most of `memory/library/` is FROZEN Midnight AI history and
carries a status banner.

**Why this map exists.** Kevin, 2026-08-07: a paper camping checklist he wants to photograph into
the app, plus notes and reminders generally. Grilling it out revealed the ask was three overlapping
things the app already half-had (`CarTask` is a working checklist that only knows about cars,
`PlaceReminder` is a working geofenced reminder, `MemoryEntry` is a free-text store) plus two it
did not (a calendar, and any way to make a list from a photo).

**This map has NO external blockers.** That is deliberate and was bought by ruling Google out (see
Out of scope). Everything here is buildable today, unlike Drive sync, which has been blocked on an
unregistered OAuth client since 2026-08-01.

**Skills each session should consult:** `/grilling` and `/domain-modeling` for the HITL tickets,
`/prototype` for prototype tickets, `/research` for research tickets.

**Standing preferences for this effort (Kevin, 2026-08-07):**
- Simple first, per the `legion-shape` map's standing preference. Still applies.
- Recurrence was deliberately taken IN despite being the most expensive decision on the map,
  on the argument that retrofitting it onto a flat events table is worse than designing for it.
  Kevin was shown the cheaper "second wave" alternative and declined it.
- Nothing that requires a Kevin-hosted backend, per CLAUDE.md §7. Nothing here needs one.

### Settled while charting (Kevin, 2026-08-07) - binding on every ticket

These are constraints, not open questions. A ticket that contradicts one of these is wrong.

| # | Decision | Consequence |
|---|---|---|
| 1 | **Absorb, don't sit alongside.** `CarTask` and `PlaceReminder` fold into one general domain. | A `car_tasks` migration is mandatory, not optional. Sync ids and tombstones must survive it. |
| 2 | **One model.** A list owns items; a note is a list whose items do not tick. | One editor, one voice grammar, one ingestion target, one sync path. A long prose note becomes one long item - accepted cost. |
| 3 | ~~**The human is the reconciliation gate for a photographed list.**~~ **WITHDRAWN 2026-08-07** when photo ingestion was cut. | No longer applies to anything. Nothing on this map now ingests an outside document, so no §4 narrowing is needed and none is claimed. **This decision must not be cited by a future feature** - it was withdrawn before it was ever built on. |
| 4 | **An item carries at most one optional trigger**, a time or a tagged place, never both. Most items have neither. | |
| 5 | **Alarms are local.** Not outsourced to Google Calendar. | Android alarm plumbing is this map's largest new-platform-surface item. Ticket 03. |
| 6 | **A calendar event is the same entity as a list item**, with optional `startsAt`/`endsAt`. | The calendar screen is a view over rows that have a `startsAt`. |
| 7 | **Recurrence is IN scope.** | The single most expensive decision here. Ticket 04. |
| 8 | **All Google integration is OUT.** | See Out of scope. |

## Decisions so far

<!-- one line per closed ticket -->

- [What is the entity model, and what happens to `car_tasks`?](issues/01-entity-model-and-cartask-migration.md)
  — **`ItemList` + `ListItem`, Room v9 -> v10.** An item with a `startsAt` is an event; at most one
  trigger (time or place). **`category` is dropped** and all car tasks land in one list, "Car".
  Migration copies `car_tasks` and `place_reminders` preserving `syncId`/tombstones and **does not
  drop either source table** - that comes later, separately.
- [How does recurrence work?](issues/04-recurrence-model.md) — **much cheaper than charted, because
  a recurring item cannot be ticked.** Small hand-rolled rule set (daily/weekly-on-days/monthly-on-
  date/yearly), stored as a rule with occurrences computed on read, never materialised. **Skip a
  single occurrence, never move one.** "Edit this one or all of them" never has to be asked.
- [How do you address an existing item by voice?](issues/05-voice-grammar-for-editing.md) — **fuzzy
  text match, never by position.** Ambiguous refuses and names candidates; no-match refuses and
  offers. Unnamed list defaults to **most recently used**, and Alfred always says which one he used.
  Deleting a list confirms, deleting an item does not. Notes stay out of `undo_last_log`.
- [Where does this live in the app?](issues/07-where-it-lives.md) — **one new destination, "Notes",
  with the calendar as a view inside it.** Today summarises today's items and links in; never a
  second editor. Three screens, pure resolvers for the logic.
- [What shape is the calendar view?](issues/08-calendar-view-shape.md) — **agenda, no month grid.**
  Query filters `startsAt IS NOT NULL` against an index; series expand into the visible window only,
  skips subtracted during expansion. **Decided on recommendation, not put to Kevin** - a small reopen.
- [How does this sync across two phones?](issues/09-sync-across-two-phones.md) — **it does not.
  Local-only.** `sync/` has never executed, Drive still has no compare-and-swap. Accepted cost: a
  shared camping list is not shared, and the UI must say so. Sync columns are still carried, and
  when sync arrives it must be per-ITEM last-write-wins.
- [Do the car-task voice tools survive absorption?](issues/10-car-task-tool-compatibility.md) —
  **no, all four retire.** Safe because car tasks were always voice-only (zero `ui/` references).
  Ticket 05's tool set must be net-neutral or better against the four removed.
- [What does a fired reminder actually do?](issues/12-what-a-fired-reminder-does.md) — **its own
  notification channel** (the two existing ones are `IMPORTANCE_LOW`, i.e. silent). Firing changes
  nothing on the item; it stays open until ticked. **SNOOZE on the notification itself.** A reminder
  missed while the phone was off is **reported in a MISSED section, never silently dropped** - that
  was rejected on this repo's history, being the same shape as `sync/` and categorization. Alfred
  speaks it aloud at a turn boundary if a live session is active.
- [How do lists get archived, and how is one reused next year?](issues/11-archiving-and-reuse.md) —
  **archived, not deleted**, behind a SHOW ARCHIVED toggle matching `CarsScreen`. A "master camping
  list" is **not a new concept**: it is an archived list you copy. A copy carries text and order,
  resets ticks, drops dates/repeats/place triggers, and gets a **new `syncId`**. Copy and original
  are independent - the master does not learn.
- [What is the alarm mechanism on current Android, and what does it cost?](issues/03-android-alarm-mechanism.md)
  — **no exact alarm needed, so no new user-facing permission.** `setAndAllowWhileIdle` by default
  (inexact, permission-free, Doze-exempt); `setExactAndAllowWhileIdle` only for items the user marks
  exact, downgrading in words when refused. Declare `SCHEDULE_EXACT_ALARM` + `RECEIVE_BOOT_COMPLETED`,
  not `USE_EXACT_ALARM`. WorkManager ruled out on facts. **Recurrence must re-arm on fire**, and a
  chain broken by a powered-off phone stops rather than skips.

## Not yet specified

In scope, but not sharp enough to ticket. Graduates as the frontier advances.

Both the archiving patch and the templates patch graduated 2026-08-07 into
[How do lists get archived, and how is one reused next year?](issues/11-archiving-and-reuse.md),
which is resolved. "What a fired reminder actually does" graduated the same day into
[What does a fired reminder actually do?](issues/12-what-a-fired-reminder-does.md) - it only became
specifiable once ticket 03 settled the alarm mechanism, and it is **the last open decision on the
map**.

One patch remains, and it is a verification rather than a decision:

- **How late an inexact alarm actually is on Kevin's phone.** Ticket 03 makes
  `setAndAllowWhileIdle` the default for every reminder, but Android's guide never restates the
  one-hour lateness bound for that specific call - it only states it for inexact alarms generally.
  It needs measuring on the real device before any delivery promise is made in the UI or by Alfred.
  Blocked on ADB being re-paired, so it is a build-time verification rather than a decision, which
  is why it sits here rather than as a ticket.

## Out of scope

Ruled beyond this destination. Never graduates; returns only as a fresh effort.

- **Photo ingestion of a handwritten list.** Cut by Kevin 2026-08-07, mid-effort, after the map had
  been charted around it. Closes two tickets:
  [Can the model actually read Kevin's real handwriting?](issues/02-can-it-read-the-real-handwriting.md)
  and [What does the photo-to-draft flow look like?](issues/06-photo-to-draft-flow.md). **Neither
  was resolved - both were ruled out**, so neither appears in Decisions so far. Nothing was learned
  about whether the model can read handwriting; the probe was built but never run, and the question
  remains genuinely open should this ever return. Retires charting decision 3 with it.
- **Google Calendar mirroring**, one-way or two-way. Ruled out 2026-08-07 after being scoped in and
  then removed. The local model should avoid designing itself into a corner, but no mirror decision
  is made here.
- **Gmail and any wider Google account access.** Kevin raised it while charting; split off as its
  own future map. It shares exactly one dependency with this one (the OAuth client) and nothing
  else. **That map cannot start until the OAuth client is registered** - the same blocker that has
  held Drive sync since 2026-08-01. Research it needs first: whether an OAuth client in Testing
  status with external user type really does expire refresh tokens after 7 days, which would make
  weekly hand re-authorization mandatory and probably kills the idea.
