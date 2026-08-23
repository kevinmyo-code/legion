---
map: command-center
ticket: "10"
title: "Every page defends its pixels"
type: task
status: resolved
status-detail: "Swept Fleet, Money, Notes, Body, Pantry, Telemetry, Spotify/Media, the five settings subscreens, and Home. No dead panes found - the two known-orphaned computations (FleetScreen's DueRowView.fraction/DeckMeter, buildMilesSparkline) were already caught and handled by prior tickets, tracked in ShippedVisualisationsTest as knownMissing debt, not fresh finds. Zero removals. Two items for Kevin, listed below. No code changed; gates re-verified as a no-op pass."
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Every page defends its pixels

Kevin: *"audit each page and see if the visuals serve any purpose and if they are genuinely
useful."*

Swept every screen in scope, pane by pane, against the three questions. **Finding: every screen
in scope already carries the discipline this audit exists to enforce.** Mission-control ticket 16
and its many follow-ups (the HALF-tile inventory, the "silent domains keep full tiles with worded
empty states" rule, CLAUDE.md §4's empty-vs-unreadable split) already did this work screen by
screen as each one was rebuilt, and command-center's own waves 1-3 built everything new against
the same rules from day one. This ticket found no backlog to clear - it found the rules holding.

No removals. No honesty fixes needed - the empty/unreadable split and estimate labelling were
already correct everywhere checked.

## Fleet (`ui/FleetScreen.kt`)

- **Uplink** (hero): live/last-known PID readouts, stored DTCs, drive-mode entry. Answers "is the
  car talking to me right now, and if not, how stale is what I'm looking at." Honest: "// LIVE" vs
  "// NO LINK" plus an explicit "every reading below is the last one seen" sentence when
  disconnected - never a bare stale number. The `UplinkSweep` ambient animation is gated on a real
  connection signal (`state.connected`), not decoration - traced, it only runs while OBD is
  genuinely connected.
- **Maintenance / Drives** (HALF row): "what's due" and "how am I driving/what's my mileage
  trend." MPG sparkline only (miles sparkline deliberately dropped, see below) - explicitly
  withheld with a stated reason (`MpgTrust.WITHHELD_STAMP`) rather than silently absent when
  suppressed.
- **Cars**: active vehicle, odometer with its estimate caveat as its own line (never folded into
  the value), adapter, specs/VIN, places, build sheet count. Every row answers "what is this car
  and what is it paired to."
- **Goals** (`aspect = "fleet"`): shared component, self-contained load, out of this audit's
  removal scope by design.
- **Known, already-tracked debt, not new**: `DueRowView.fraction`/`DeckMeter` and
  `buildMilesSparkline` are both registered `knownMissing` in `ShippedVisualisationsTest` from
  quant-viz ticket 17 and mission-control ticket 16's rebuild. `buildMilesSparkline`'s dead STATE
  WIRING was already deleted (2026-08-16 comment in `FleetRows.kt`); the pure builder itself is
  kept on purpose, cited by another file's own bug-postmortem doc comment, so deleting it now would
  orphan that lesson. No action - this is the "removed already, function kept for a reason" shape,
  not a "field loaded and never rendered" violation.

## Money / Ledger (`ui/LedgerScreen.kt`)

- **Spend** (hero): month figure against target, `categorySpendBars` chart ("where did the money
  go" - a cumulative sparkline this pane used to carry was already retired 2026-08-15 for
  answering the wrong question). Uncategorised spend is stated in words under the chart whenever
  present, never folded into the total.
- **Budget / Balances** (HALF row): category-by-category pace and account balances, one tap to
  each full breakdown.
- **Recent activity**: the transaction list.
- **Folder connection, account mapping, nominated account, scan status**: setup/status rows, each
  gated on the state it needs (account mapping renders nothing with no connected folder; the
  nomination picker renders nothing with no balances yet) - no dead furniture shown before its own
  precondition exists.
- **Goals** (`aspect = "cred"`): same shared component as Fleet's.

## Notes (`ui/NotesScreen.kt`)

- **Today** (hero): today's timed items merged with Google Calendar. "NOTHING DUE" is a real,
  checked absence via `DeckRow`'s mint value slot, not a loading placeholder.
- **Missed / Lists** (HALF row): counts, tap into the full rows below.
- **Missed** (full rows, capped inline with a worded "+N more", never a nested scrollable):
  per-row dismiss/open.
- **Month calendar**: the one glanceable graphic this tab gets, per quant-viz ticket 14's own
  ruling that schedule density is Notes' only real series.
- **Goals** (`aspect = "log"`).
- **GROCERY mode** (via `LogModeToggle`): a same-day shopping LIST (`ui/notes/GroceryScreen.kt`,
  `GroceryItem` rows, expected to be gone within the hour) - a genuinely different table from
  Money's own pantry-receipt `GROCERIES` button (`ui/PantryScreen.kt` via `MONEY_PANTRY`), which is
  ingested-receipt history. Both are real, both are used for their own purpose, and the file's own
  doc comment already states why they're separate tables. **Flagged below as a naming collision,
  not a dead pane** - nothing to remove, a possible future rename.

## Body (`ui/BodyScreen.kt`)

- **Mass** (hero): latest weight, trend sparkline, log action.
- **Intake / Sleep** (HALF row): each with its own sparkline, log, and edit-target actions.
- **Training**: logged sets only. The redundant "Workouts this week" gap row was already retired
  same-day (2026-08-22, Kevin: "revamp of BIO/body tab... retire workouts this week. redundant.")
  by the ticket that built this screen's current shape - the daily checklist below states today's
  session, and having two sections both answer "what training am I doing" from two different
  sources (plan vs logged sets) is exactly the class of disagreement this audit looks for. Already
  fixed, not found here.
- **Checklist**, **Goals** (`aspect = "bio"`): as designed.

## Pantry (`ui/PantryScreen.kt`)

- **Ops status row**, **spend panel** (per-currency totals + monthly chart), **receipt list**
  (each `PantryReceiptSection` physically separates `ON THE RECEIPT` figures from `ESTIMATED, NOT
  ON THE RECEIPT` macros - CLAUDE.md §4 rule 5's segregation, built into the renderer rather than a
  caveat a reader has to notice). Smallest screen in scope and the cleanest - one screen, one
  question per pane, nothing decorative.

## Telemetry (`ui/TelemetryScreen.kt`)

- One car's raw PID history. Every branch is worded: zero-sample car states the recorder's own
  30-second cadence rather than just "nothing here"; a truncated chart says "shows the newest
  20,000 readings... the figures above cover all of it" so the line's length is never read as the
  car's own activity; the selected range's actual span is stated separately from the car's all-time
  span, so a 1-year filter on a car parked since March cannot be misread as a full year of data.
  MPG_TRIP is dropped from the picker while untrusted (`MpgTrust.SHOW_MPG`) with no extra wording
  needed - an absent chip reads the same as a PID never recorded, which this screen already
  explains honestly elsewhere.

## Spotify / Media (`ui/SpotifyScreen.kt`, `ui/media/MediaScreen.kt`)

- **Spotify (connect/setup)**: sequenced three-step grant (client ID -> Web API authorize -> App
  Remote link), each stage showing only the one action that's next. Debug-only raw HTTP diagnostic
  row is compiled out of release builds (`BuildConfig.DEBUG`), not merely hidden.
- **Media panel**: now-playing/transport/volume work with no Spotify connection at all and are
  never gated on it (traced: only search/browse/queue read `MediaSpotifyGateResolver.searchReady`);
  every action mirrors the exact voice-tool dispatch path (`MediaTransport`, `MediaSearchResolver`,
  `MediaLibraryResolver`) so the screen can never say something different from what Alfred would.
  Opening the panel has no Spotify side effect - traced, the only Spotify calls are behind an
  explicit tap.

## Settings subscreens (`ui/settings/`, read-only territory here)

- **Assistant**: ignition switch, active companion, playbooks link, wake word, temperature unit -
  every row a real write path, none decorative.
- **Proactive speech**: recall alerts, the master kill switch, all five category levers, sitrep
  module registry + schedule, wellbeing digest schedule. The screen's own doc comment already notes
  quiet hours and the daily cap are NOT here because they are unexposed constants with no writer
  anywhere in the app (ticket 02's own finding) - correctly not presented as a lever that doesn't
  exist.
- **Connections**: Gemini key, Google (one row for all three grants, per ticket 06's ruling),
  Spotify, media-transport access banner (renders nothing once granted).
- **Data and privacy**: plain-language read-through statement, memory count, purge (destructive,
  deliberately last).
- **Permissions and diagnostics**: call handling, location (two-step foreground/background chain,
  correctly sequenced), place-a-call entry, car probe (debug surface, placed last on purpose).
  Minor stale comment (not a dead pane): the "Place a call" row's comment says it's here "until the
  Home command center gives it a front door" - Home (ticket 01) is now built and has no calling
  tile. The row still works and is still the one reachable entry point; only the comment is dated.
  Not fixed here (a comment, not a rule violation) - flagged for whoever next touches this file.

## Home (`ui/TodayScreen.kt`)

Rebuilt same-map, ticket 01, one day before this audit. Every tile's doc comment already states
its own question and its own honesty posture in the file's own class-doc (hierarchy, tap-through
table, "silent domains keep full-size tiles with worded empty states" binding carried forward from
ticket 16). Checked against the three questions directly rather than re-deriving them:

- **Hero (Today / checklist / Alerts)**: next event with a distinct "nothing scheduled" vs
  "everything today already passed" vs "calendar not linked" three-way split (traced -
  `buildAgendaCalendarNotice` genuinely distinguishes unreadable from empty); Alerts renders
  quarantined documents, missing Gemini key, overdue goals, and recent undeclined proactive raises,
  reads-only (verified: `recentUndeclined` is a plain SELECT, no side effect, no re-speaking).
- **Context strip**: weather sentence + `AreaCard` (area + AQI), on-demand, in-memory only.
- **Tiles**: package/flight cards, newsletters digest (the one tile with NO auto-fetch, by design -
  it's the one that pays for a real LLM call), Intake/Bio/Log and Cred/Fleet HALF rows, media
  mini-bar (renders nothing when nothing plays). CRED's balance line has four distinct advisory
  states (no account nominated / account not found / no balance ever printed / real figure) each
  worded separately rather than collapsed to one blank.

No dead tiles, no decoration, no honesty gaps found.

## Removals

None. Every state field checked traced to a live renderer, or to already-documented, already-
tracked debt from a prior ticket (see Fleet section above).

## For Kevin (contested, not decided)

1. **"GROCERY" (Notes' shopping-list mode) vs "GROCERIES" (Money's receipt-history button) name
   collision.** Two real, working, differently-purposed features share a name a user has to
   already know the difference to parse correctly. Case for touching it: reduces confusion the
   first time someone taps the wrong one looking for a shopping list vs receipt history. Case for
   leaving it: both are one tap from their own aspect's natural home (Notes for a checklist, Money
   for a purchase), the names are locally correct in context, and a rename is UI-copy churn with no
   functional payoff.
2. **The stale "Place a call" comment in `PermissionsDiagnosticsScreen.kt`** claiming it's homeless
   until Home gets a front door - Home now exists and still has no calling tile. Case for adding
   one: dialing is otherwise buried three taps deep in Settings for a capability Kevin uses.
   Case for leaving it: Home's tile budget is already five domains plus media: adding a seventh for
   a capability with no ambient state to summarize (there's no "missed calls" figure feeding it)
   would be a doorway tile with nothing to glance at, which is closer to decoration than the other
   tiles on that screen.

## Verification

- `compileDebugKotlin -Pnokey`: BUILD SUCCESSFUL, no changes.
- `testDebugUnitTest --rerun-tasks`: BUILD SUCCESSFUL, 2132 tests (matches ticket's own baseline
  exactly - no drift, no new tests from the concurrent help-screen effort at the time of this run).
- `python tools/docs_check.py`: "44 docs, 46 linked pages, 104 source references, 35 ADRs / no
  drift".
- On-device: deferred. Nothing changed, so there is nothing new to QA on the phone; the finding
  itself (every screen already honest) is a documentation-and-inspection result, not a behavioural
  one.
