---
map: command-center
ticket: "13"
title: "The information leads, the plumbing sinks"
type: build
status: open
status-detail: "3 of 5 findings done (4, 2, 3); 1 and 5 not started. GapEmptyRowTest written but never run - see Handoff."
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---
# The information leads, the plumbing sinks

Kevin, 2026-08-22: *"not just polish, the UX itself, the way the information is displayed etc."*

Grounded in real screenshots off the A25 (scratchpad `shots/`), not taste. Five findings, each
with its evidence:

## 1. Money leads with plumbing

The tab's ENTIRE first screenful is folder-connection controls, an account-mapping explainer
paragraph, a raw 16-digit folder identifier, and the nomination protocol. Balances, month spend and
budget - the reason the tab exists - are below the fold.

**Fix: payload first, plumbing last.** Balances / month spend / budget vs actual lead the screen.
Folder connection, account mapping and nomination move to the bottom or behind a SOURCES drilldown.
Nothing is deleted - it re-ranks.

## 2. Manual prose rendered as furniture

Paragraphs of doc-comment register ("A file that doesn't state its own account (a CSV export)
takes the account mapped to the folder it's in...") render permanently. Read once, scrolled past
forever.

**Fix: a collapsed help affordance** - a small `?` or WHY row that expands the explanation on tap,
one shared composable so every screen's help behaves identically. The words survive; they stop
being furniture.

## 3. Empty states teach voice while the button whispers

INTAKE renders a giant SAY "LOG A MEAL" with the new `+ LOG` button small beneath it. ADR 0035
made the button the reliable path; the hierarchy is backwards.

**Fix: affordance first.** The button is the empty state's primary line; the voice phrase drops to
a secondary caption ("or say: log a meal"). Same inversion on every empty state that names a voice
phrase - grep for SAY " to find them all.

## 4. Sparklines render broken at low N

MASS is two-thirds void with one floating dot (a one-point sparkline). SLEEP shows a stray dot,
INTAKE a floating diagonal. They read as rendering bugs, not data.

**Fix:** the shared sparkline hides below 3 points, and panes sized for a chart collapse to
content height when the chart is hidden. One rule in the shared component, not per-screen.

## 5. Row grammar drifts

TRAINING: "3 sets" beside "3 sets x 10 @ 30.0lbs" (also decimal noise - 30.0lbs is 30 lbs).
Alerts tags misaligned per row (pane already deleted). TODAY // 21:29 an inch from the status
line's 21:29 - the same clock twice.

**Fix:** one formatter per fact family (weights, set lines, clocks) in a shared file; the Today
header drops its clock (the status line already owns it).

## Rules

- Re-rank and re-house; delete nothing that carries information (the alerts pane is already gone).
- Mission-control palette and components unchanged - this is hierarchy, not a theme change.
- Empty vs unreadable sentences survive every move.
- Verified by BEFORE/AFTER screenshots off the phone, not by adjectives - the before set already
  exists in the scratchpad.

## Handoff, 2026-08-26 - findings 4, 2, 3 done; 1 and 5 not started

Picked up mid-effort. Read this before restarting: two of five findings are fully verified, one is
committed with a named owed step, two are untouched.

| Finding | State | Commit |
|---|---|---|
| 4 sparklines at low N | **DONE**, suite green (2,656 tests, 0 failures) | `af9db89` |
| 2 prose as furniture | **DONE**, `HelpRowTest` 4/4 green | `11f4d57` |
| 3 empty-state hierarchy | **BUILT**, production compiled green, `GapEmptyRowTest` never run | `66287c2` |
| 1 Money leads with plumbing | **NOT STARTED** | - |
| 5 row-grammar drift | **NOT STARTED** | - |

### Why finding 3's test never ran, and what to do about it first

Two agents shared this working tree and its `app/build/` while the backend arc was in flight.
Gradle assumes one writer per build directory. Six distinct infrastructure failures, none of them
either agent's code: an unresolved `MIGRATION_40_41` mid-edit, an `EventReplica` constructor
mismatch against its generated DAO mid-edit, a vanished `in-progress-results-*.bin`, a locked
`caches-jvm`, the build daemon stopped twice by the other process, and finally an OOM with the
build directory cleaned underneath the run.

**First action on resuming: run `./gradlew testDebugUnitTest -Pnokey --tests
"com.kevin.legion.ui.common.*"`.** `GapEmptyRowTest` is written and has never executed. Do not
treat finding 3 as resolved until it does. If the backend arc is still live, take a git worktree so
each side has its own build directory rather than fighting over one.

### What finding 4 established that 1 and 5 should reuse

The rule went into the COMPONENT, not into the callers - `DECK_SPARKLINE_MIN_POINTS` and
`deckSparklineHasShape()` in `ui/common/DeckCharts.kt`, with `DeckSparkline` returning above its
`Canvas` so it occupies no space at all. `FleetDrilldowns` had already solved this correctly for
monthly recaps and nobody else knew. Finding 5's formatters want exactly the same treatment: one
formatter per fact family in a shared file, so weights and set lines and clocks cannot drift apart
again per-screen.

### A constraint findings 1 and 5 must not break

Finding 2 nearly collapsed three things it should not have. **A trust disclosure is not furniture.**
The estimate lines in `ui/ledger/LedgerScanRows.kt` and the unreconciled/lost-photo disclosure in
`ui/pantry/PantryRows.kt` stay permanently visible - CLAUDE.md sec 4 rules 5 and 7 require them said
on the surface, in words, always. Putting one behind a tap is not a hierarchy change, it is a
quieter lie. Most other long-prose candidates turned out to be empty states, which get worse when
collapsed, not better.

The same applies to finding 1's re-rank: `money-read-state` is placed under the title "never below
the fold" on purpose, and `ScanStatusSection` is coupled to the empty-state condition below it
(a "nothing here" message beside a live progress bar reads as contradictory). Re-rank the three the
finding names - folder connection, account mapping, nomination - and leave those two where they are.
The Money tab's current item order, for reference:

`money-title-row` / `money-read-state` / `money-folder-connection` / `money-account-mapping` /
`money-nominated-account` / `money-hairline-and-scan-status` / loading-empty-listing / `goals` /
`spend-pane` / `tile-row-budget-balances` / `activity-header`

### Follow-up this effort surfaced, not in the original five

**The Body drilldowns offer `DEL` by hand but no `ADD`.** A weight log can be deleted by touch and
created only by voice, which is an ADR 0035 gap in its own right. Finding 3 could not close it: the
dialog state lives in `BodyPanelList` while the drilldowns are invoked from `BodyScreen`, so a
button needs that state hoisted across two composables. That is a refactor, not a hierarchy fix.
It wants its own ticket.

### Still owed on the phone, for all five findings

The ticket's own rule - "verified by BEFORE/AFTER screenshots off the phone, not by adjectives".
The before set exists in the scratchpad. **The A25 has never been attached to this machine**
(CLAUDE.md sec 6), so no finding here has been seen running. That is a gate on this ticket, not a
footnote (L11).

## Verification

- Suite green both ways, one run fresh. docs_check no drift.
- On the phone: Money opens to money; a help row expands; a fresh install's Body shows buttons
  first; no floating dots anywhere.
