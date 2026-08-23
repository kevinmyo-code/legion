---
map: hands-and-senses
ticket: "29"
title: "Service history and the maintenance clock become one fact"
type: build
status: kiv
status-detail: "KIV 2026-08-23: superseded by the aspect engine (.scratch/aspect-engine/map.md, charter decision 3)"
blockers: ["28"]
blocked-by: ["[[28-the-oil-change-it-forgot]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# Service history and the maintenance clock become one fact

Kevin, 2026-08-22, on being shown the oil-change bug: *"service history and maintenance clock
should be tied together as one object no? thats why theres the bug."*

**He is right, and it is a better diagnosis than [[28-the-oil-change-it-forgot]] made.** That
ticket described a symptom - a read that ignores a table. This is the disease: `service_records`
(the events) and `maintenance_items.lastDoneMileage`/`lastDoneDate` (the clock) are two
independent stores of one fact, kept in step by hand. Every symptom falls out of the split:

- The clock says 227,483 mi with no date; the record says 227,374 mi on 12 Aug. Both about one oil
  change. Nothing reconciled them because nothing had to.
- A mileage-only write nulls the clock's date, and the record it contradicts sits untouched.
- The Service History screen and the voice answer read different tables, so the app can show a
  service on screen and deny it out loud - which is exactly what happened.

## The shape

**Records are the events. The clock is a projection of them, plus an explicit override.**

1. **Derive where an event exists.** Last-done for a service = the most recent non-deleted
   `service_records` row for that vehicle and service. Ticket 28 already makes the READ work this
   way; this ticket makes it the only way.
2. **Keep an override for facts with no event.** "Done at 200k by the previous owner", "never
   done", an inherited car with a mileage and no history - these are user ASSERTIONS with no
   record behind them, and they are legitimate. They stay, but they must be distinguishable from a
   derived value rather than living in the same two columns.
3. **A logged service updates the clock by existing**, not by a second write that can drift.

## Decide while building

- **Does the override become a `service_records` row of its own** (tagged as asserted rather than
  observed, the way `IngestMethod` distinguishes provenance elsewhere in this app), collapsing the
  two stores into one table? That is the cleanest end state and it deletes the drift by
  construction. **This is the recommendation** - it reuses a provenance pattern this codebase
  already trusts.
- Or does the anchor keep its columns and gain a flag meaning "asserted, not derived"? Cheaper
  migration, keeps the split alive in a smaller form.
- **What happens to existing rows** - the Jeep's dateless 227,483 clock is a real user assertion or
  a wiped date, and the migration cannot know which. **A migration that guesses is worse than one
  that asks**: prefer preserving both facts and letting the surfaces state them (28's honesty rule)
  over silently picking a winner.
- Sync: both tables carry `syncId`/`deleted` and ride Drive. Whatever shape is chosen must survive
  a two-phone merge without resurrecting a deleted record or double-counting an event.

## Rules

- Room discipline in full: verbatim generated SQL, additive, `exportSchema`, migration test,
  `SCHEMA_VERSION` bumped with `@Database` (currently 32).
- **No fact is lost.** Every existing record and every existing anchor value survives the
  migration in a form some surface can still state.
- The Service History screen and every voice answer read the SAME source afterwards. A test must
  pin that they agree - the bug this ticket exists to kill is precisely that they did not.

## Verification

- Suite green both ways, one run fresh. Migration test.
- A test that screen and voice answer identically for the same service.
- On the phone, with Kevin's real data: the Jeep's oil change reads correctly in both places, and
  nothing else in his 54 maintenance items lost its history.
