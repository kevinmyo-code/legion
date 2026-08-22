---
map: quant-viz
ticket: 17
title: "Two shipped visualisations vanished in a later rebuild, and nothing noticed"
type: grilling
status: built
status-detail: "2026-08-21 - guard built; the two charts themselves are still missing"
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# Two shipped visualisations vanished in a later rebuild, and nothing noticed

## Question

Found 2026-08-16 during the all-effort verification sweep, not by any test. Both are features that
**shipped, were QA'd on-device, and then silently disappeared** when a later effort rebuilt the
screens around them. The pure layers survived, still compute, and are still unit-tested - they just
feed nothing.

This is a different failure from anything filed so far. The other findings today were code that
never existed or docs that lagged the code. **This is code that existed, worked, was verified on a
phone, and was removed by accident.**

### Regression 1 - the maintenance due meter

- Built by quant-viz ticket 05 Part C, commit **`7c6a5ca`**, which added
  `DeckMeter(row.fraction, ...)` to `FleetScreen`.
- **Dropped by the mission-control rebuild, commit `a09aa68`.**
- What remains: `DueRowView.fraction` (`ui/fleet/FleetRows.kt:111`, computed `:311`) and
  `ScheduleRowView.fraction` (`:378`, `:417`) are **orphaned data with no renderer**. `dueFraction`
  is still pure and still unit-tested (`FleetRowsTest.kt:147-187`).
- Repo-wide `DeckMeter` callers today: `GoalsPanel`, `BudgetSection`, `TodayScreen`, `ThemePreview`.
  Fleet is not among them.

### Regression 2 - the DRIVES miles sparkline, plus a comment that lies about it

- `buildMilesSparkline` (`ui/fleet/FleetRows.kt:947`) is computed into
  `FleetUiState.milesSparkline` (`FleetScreen.kt:504`, field `:281`) and **read by no composable**.
  The DRIVES tile renders MPG only (`:833-842`).
- **The comment at `FleetScreen.kt:824-826` claims "DRIVES' own drilldown still carries both series
  in full". It does not.** `DriveHistoryDrilldownScreen` (`FleetDrilldowns.kt:994-1029`) has no
  chart at all. So the code actively misleads the next reader about where the series went.
- There is no test for `buildMilesSparkline`, while `buildMpgSparkline` has one - so nothing would
  have caught the loss even in principle.

### A third, lesser one for context

quant-viz ticket 11's **SLEEP sparkline** left Today when SYSTEMS SWEEP was dissolved by the same
commit `a09aa68`. That series now renders on `ui/BodyScreen.kt:439-440`, so it is **relocated, not
lost** - but ticket 11's own stated requirement no longer holds, and nobody recorded the move.

## Decide

1. **Restore, or accept the loss?** The due meter is a real capability - it is how a maintenance item
   shows how close it is without reading a number, and Kevin's standing glanceable ruling ("im not
   gonna read numbers. it has to be glancable") argues for restoring it. The miles sparkline is a
   weaker case: the DRIVES tile may be better with one series than two.
2. **If not restoring, delete the orphans.** `DueRowView.fraction`, `ScheduleRowView.fraction` and
   `buildMilesSparkline` should not sit computed-and-unread with passing tests implying they work.
   That is the pattern that produced six other orphans found today.
3. **Fix the lying comment regardless.** `FleetScreen.kt:824-826` must not claim a drilldown carries
   a chart it does not have. That is true whichever way 1 and 2 go.
4. **How does a rebuild avoid dropping a shipped feature next time?** This is the real question. A
   screen rebuild replaced composables wholesale and the only survivors were the pure functions, so
   the tests all stayed green while the UI lost two features. Options worth arguing: an inventory
   check in a ship pass, a test that asserts a state field is read, or simply accepting that screen
   rebuilds need a before/after screenshot pass - **which is how these were found in the first
   place**, several days late.
5. **Is anything else missing?** Two were found by auditing 16 tickets. The same rebuild touched
   HOME, CRED, FLEET and NOTES. Nobody has checked the rest.

## Swept 2026-08-19 - four of the five are done, and the ticket is now only decision 5

Verified against the tree, not against this ticket's own notes:

- **Decision 1 (restore or accept the loss): DONE.** `0194e5f`. The meter is back, relocated to where
  the rows actually live now - `FleetDrilldowns.kt:216-219` (`MaintenanceDrilldownScreen`) and
  `:531-532` (`ScheduleRow`), both feeding `row.fraction` into `DeckMeter`. The old
  `FleetScreen`/`MaintenancePane` the rebuild deleted is not where it went back.
- **Decision 2 (delete the orphans or keep them): DONE.** `buildMilesSparkline` is kept deliberately
  with the reason written on it (`FleetRows.kt:948`, "NO CURRENT RENDERER, deliberately kept"),
  rather than silently orphaned - which is the L27 failure this map already learned once.
- **Decision 3 (the lying comment): DONE.** `FleetScreen.kt:820` no longer claims a chart the
  drilldown does not carry.
- **Decision 4 (stop it recurring): DONE.** `ui/fleet/FleetDrilldownsMeterRenderTest` exists, two
  tests, scanning `FleetDrilldowns.kt`'s own source for `DeckMeter(row.fraction`. The regex it
  requires was compared against the current source and matches.

**Decision 5 is the whole of what is left, and it is the biggest one:** whether anything ELSE
vanished in the same rebuild, across HOME, CRED, FLEET and NOTES. No sweep artifact for that exists
anywhere. Two features were lost silently with their pure layers still green; nobody has checked
whether there was a third.

**So this ticket stays open on purpose, narrowed to that audit.** Closing it on the strength of the
four that are done would retire the question it exists to answer.

## Resolution - 2026-08-21 (Kevin)

**A test that names each screen's expected charts and fails the build when one disappears.**

Same posture as the two guards that already earned their place today: `PromptRoleNamingTest`, which
caught 183 real leaks, and the exact-set drift guard on `EPISODIC_EXCLUDED_TOOLS`, which failed the
moment a fourth name was added and forced that addition to be a written decision. **This turns
"nothing noticed" into "the build noticed."**

Screenshot tests were rejected as the wrong tool here: they catch far more - layout, theming, empty
states - and they are famously noisy on a one-developer project with no CI. A noisy guard gets
disabled, and a disabled guard is worse than none because it still looks like coverage.

**What it cannot catch, stated plainly:** a chart that is present but rendering nothing, or bound to
the wrong data. Presence is the cheap half. The regression that prompted this ticket was two charts
**vanishing entirely**, which is exactly what presence catches.

## Built - 2026-08-21

`ui/ShippedVisualisationsTest.kt`. A registry of shipped visualisations - screen file plus the
symbol that proves it renders - scanned from SOURCE, because a unit test cannot render Compose. Same
shape as `PromptRoleNamingTest`, which caught 183 real leaks by reading files rather than running
them.

**Verified by planting a loss**, not by assuming: renamed `TodayScreen`'s expected symbol and watched
the guard fail, then reverted. A guard nobody has seen fail is not a guard.

### The two charts are STILL MISSING, and the test says so out loud

This built the alarm, not the repair. `FleetScreen` has zero `DeckMeter` calls and `milesSparkline`
still has no reader.

Rather than pretend otherwise, they sit in the registry flagged `knownMissing`, and a second test
asserts **they are still missing**. When someone restores one, that test FAILS and forces the flag to
be flipped. A known gap that quietly heals is a gap nobody records closing.

### A stale path found on the way

This ticket's own text says the meter lives in `ui/fleet/FleetScreen.kt`. **It is `ui/FleetScreen.kt`**
- there is no `ui/fleet/` directory. The registry copied the ticket, the test failed on a missing
file, and that is how it surfaced. Worth noting because the ticket is otherwise precise, right down
to the commit SHAs.

### What it cannot catch, stated rather than implied

A chart that renders but is bound to the wrong data, or renders nothing because its input is empty.
**Presence is the cheap half** - and it is the half that failed here, twice.
