---
map: one-home
title: "One home: Meters folds in, Calendar becomes HOME, and the day's list is written for you"
charted: 2026-09-10
charted-by: "Kevin + Opus"
effort: "`.scratch/one-home/`"
tickets: 8
open: 8
status: open
tags: [map]
---

# One home: Meters folds in, Calendar becomes HOME

**Kevin, 2026-09-10, on the Android app:** *"calendar home page > today's plan > no need since we
have bio to do list. the advisor would tell me what would be a good daily todo list for workouts >
and populate it that way. A news feed page > pulled from my gmail or just rss feeds. thinking of
retiring meters page. just everything on home page (rename it from calendar)"*

Four asks in one sentence, and they are not the same size. Two are a deletion and a rename. One
turns out to be **the same mechanism it asks to delete, re-pointed** - and that is the finding that
shaped this map. One is a new ingestion path with an undecided rule attached.

## What is true today

`ui/LegionRoute.kt:322` - **there are exactly two top-level tabs**, `CALENDAR` and `METERS`.

They are not a bottom bar. `LegionTabRow` (`ui/MainActivity.kt:1123`) is a custom text-only `Row`
sitting at the TOP of the screen under `StatusLine`; there are no icons, only uppercased labels.
Settings is not a tab at all - it is the `SETUP` stamp on the status line, and that is deliberate:
it was briefly a third tab on 2026-09-01 and came off within hours (*"setup is being duplicated.
keep the top right corner one and drop the one beside meters"*).

`CALENDAR` is the start destination. `METERS` is the "view C" of that same cutover - at-a-glance
meters that tap THROUGH to `money`/`body`/`fleet`/`pantry`/`checklists`, which is what *"those we tap
through from view C the meters"* meant. Those destinations are still registered in the `NavHost`
(`MainActivity.kt:734-998`); they stopped being tabs, they did not stop existing.

**So retiring METERS leaves one tab, and a tab row with one tab in it is not a tab row.** That is not
a side effect to be discovered during execution; it is the first decision on this map (ticket 01).

### `MetersScreen.kt` is 935 lines and is not only meters

`MetersContent` (`MetersScreen.kt:264`) is a hardcoded `Column` of `DeckPane` blocks in source
order. **It is not data-driven** - nothing there loops over a table, so folding it into home is a
code change, not a migration. (The `widget_instances` / `DASHBOARD` pager IS data-driven, and is a
different, still-live, opt-in surface. This map does not touch it.)

Deleting the file without rehoming these deletes the capability, not just the tile:

| Pane | Line | Also reachable? |
|---|---|---|
| Needs you (breaches), Body, Money, Fleet | 319, 332, 367, 505 | Each summarises a screen that exists. The pane is the way IN, not the only copy of the data |
| Lists, Recordings | 583, 616 | Screens exist. `RecordControlRow` is shared with `VoiceNotesScreen.kt:102` - **not** orphaned |
| **Ask** - the closed-enum query picker | 658 | **No. `GeneratedViewQueryRunner.run` has exactly one production call site: `MetersScreen.kt:697`** |
| **Newsletters** (`NewsDigestCard`, private) | 877 | **No. Private to this file, and the only on-demand trigger for `SitrepModule.NEWS`** |
| **`AreaCard()`** | 723 | **No. One call site in the whole tree** |
| weather line, `MediaMiniBar` | 717, 734 | Rendered here; the media bar has its own component |

**The Ask panel is what makes this a gated deletion rather than a cleanup.** ADR 0035: a voice
capability with no hands path is not finished. `show_generated_view` becomes voice-only the moment
`MetersScreen.kt` is deleted. `GeneratedViewHost` (`MainActivity.kt:1027`) does not save it - that is
an app-wide overlay that RENDERS a result already built, not a builder. Ticket 02 rehomes before
ticket 03 deletes, and that order is the whole point.

### "Today's plan" is the mechanism Kevin is asking to rebuild

The one thing in the codebase called today's plan is `GoalChecklistPanel`
(`CalendarScreen.kt:546`, rendered only on today's own day). It is the **BIO daily checklist**:
lines derived by `advisor/GoalChecklistSync.kt` from whatever `generate_goal_plan` /
`accept_goal_plan` last wrote, stored as ordinary `list_items` rows tagged with
`GoalChecklistSync.ITEM_PREFIX`, ticked through `NotesController`.

Read the two halves of Kevin's sentence against that:

> *"today's plan > no need since we have bio to do list"* — delete `GoalChecklistPanel`
> *"the advisor would tell me what would be a good daily todo list for workouts > and populate it that way"* — which is what `GoalChecklistSync` already does

**These are not in conflict, and reading them as a contradiction would be the mistake.** The
capability is right and its STORAGE is wrong. `GoalChecklistSync` fakes a recurring list inside the
ad-hoc `list_items` table using a string prefix to find its own rows - `one-today` ticket 09 says so
in those words, and calls the prefix how it "tells its own" items apart. Meanwhile a real recurring
checklist table exists (`checklists` + `ChecklistTick`, `ChecklistController`), and the calendar
already renders a section per recurring checklist beside the goal panel.

So the day view shows **two mechanisms for the same idea**, and Kevin is asking for the one that is a
prefix hack to stop being a separate thing. The work is: the advisor keeps proposing the day's
workout list, and it lands in `checklists` like everything else. Tickets 04 and 05.

## The four asks, sized honestly

| Ask | Status | Ticket |
|---|---|---|
| *"retiring meters page, just everything on home page"* | Two tabs become one. Three things need a new address first, one of them under ADR 0035 | 01, 02 |
| *"rename it from calendar"* | Mechanical, but touches routes, the start destination, deep links, alarm targets, tests and docs | 03 |
| *"today's plan > no need"* | It is `GoalChecklistPanel`. Retiring it retires the only surface for `accept_goal_plan`'s output, so 05 must land with it | 04 |
| *"the advisor would populate it that way"* | The write door exists (`AdvisorProposalExecutor`, allowlisted handlers incl. `createWorkoutPlan`, `addTask`). **No handler writes a checklist.** That is the gap | 05 |
| *"a news feed page > gmail or just rss feeds"* | Gmail half exists (`NewsDigestCard`, command-center 12, built). **RSS does not exist anywhere** - grep for rss/atom/feed returns zero | 06, 07 |

## What this map does NOT reopen

- **`one-today` 08 and 09 stay where they are.** 08 (an event passes, a task gets done) and 09 (a
  list you tick every day) are the substance behind *"we have bio to do list"*, they are already
  charted, already `ready`, and re-chartering them here would be the second copy that goes wrong.
  **Ticket 05 depends on 09's table decision** and says so rather than restating it.
- **The design language.** Mission-control tokens, `DeckPane`, `DeckRow` - untouched. This map is
  about where things live, not what they look like.
- **Voice.** No tool is added or removed. Every screen here is a hands path to something that already
  has a voice path, or (ticket 02) is rescuing one about to be lost.
- **`dashboard/`, the widget pager.** Still demoted, still reachable from a Settings row, still not a
  tab. Kevin field-tested it overnight on 2026-08-25 and ruled *"revert everything to classic"*.

## The rule that binds the news feed, stated before it is built

CLAUDE.md §7: **third-party content is read-through only.** Mail is the named case - read to answer,
then dropped, never to Room, never synced, never remembered, not even a summary. `NewsDigestCard`
already complies (command-center 12: *"No Room row, no cache file, not even the summary"*).

**RSS is not obviously the same thing, and ticket 06 has to rule rather than assume.** The §7 line is
provenance, not subject: mail arrives unasked, which is why it is constrained. A feed Kevin
subscribes to is closer to the voice-note carve-out (ADR 0041) - it exists because he chose it. That
argues a feed's items MAY be stored. It is still other people's writing, which argues they may not.
**Nobody has decided, so nothing is persisted until 06 says so**, and 07 is blocked on it rather than
shipping a cache and asking afterwards.

§4's numeric gate has no purchase here - a headline states no total. Nothing read from a feed or from
mail may become a ledger row, a macro, or any asserted figure without going through that aspect's own
ingestion path.

## What breaks, measured rather than guessed

- **No screenshot test covers either screen.** The six Roborazzi tests under `test/.../screenshot/`
  reference neither `Meters` nor `Calendar` (grep, zero matches). The visual-regression suite is not
  the constraint here.
- **`ui/MetersScreenTest.kt` is 14 tests** and dies with the screen. Its assertions about breach
  building, budget sentences and hero values are about functions (`buildMeterBreaches`,
  `moneyUncategorizedSentence`, `groceriesHeroValue`) that should MOVE, not die - ticket 02.
- Calendar-side: `ui/CalendarDayRecordedSectionTest.kt`, `calendar/NoCalendarContractTest.kt`,
  `calendar/OpenerCalendarBriefingTest.kt`, `ui/notes/CalendarAgendaResolverTest.kt`.

## The tickets

| # | Type | What | Blocked by |
|---|---|---|---|
| 01 | decision | One tab or none: what the shell is when METERS is gone, and where the drill-downs are reached from | - |
| 02 | build | Rehome the orphans BEFORE anything is deleted - the Ask panel first (ADR 0035), then `NewsDigestCard`, `AreaCard`, and the pure functions `MetersScreenTest` covers | 01 |
| 03 | build | `CALENDAR` becomes `HOME`: route, label, start destination, deep links, alarm targets, tests, docs. Then delete `MetersScreen.kt` | 02 |
| 04 | decision | `GoalChecklistPanel` retires and `GoalChecklistSync` stops owning a prefix. Which table the advisor's day-list lands in, sequenced against `one-today` 09 | - |
| 05 | build | An `AdvisorProposalExecutor` handler that writes a recurring checklist, so the workout list is proposed and accepted through the one write door | 04 |
| 06 | decision | News feed sources and what may be KEPT: Gmail under §7, RSS under a ruling that does not exist yet | - |
| 07 | build | The news feed surface: sources, refresh on demand, three distinct failure sentences, no persistence beyond what 06 allows | 06 |
| 08 | task | Ship pass on the A25, and every claim on this map that is `reasoned` rather than `on-device` settled | 03, 05, 07 |

**Order.** 01 is Kevin's call and gates the shell. **02 before 03, always** - rehome, then delete. 04
and 06 are decisions that touch different files and can be taken alongside 02/03. 05 after 04. 07
after 06. 08 last, and it is the only ticket allowed to say the map is done.

**Execution.** One Gradle writer at a time (MEMORY: contention fakes a pass). Everything here is in
`app/`; nothing touches `server/` or `server/frontend/`, so this map can run alongside the
`web-and-households` work without a worktree collision.
