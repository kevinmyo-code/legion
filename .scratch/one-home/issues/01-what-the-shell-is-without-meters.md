---
map: one-home
ticket: "01"
title: "One tab or none: what the shell is when METERS is gone"
type: decision
status: resolved
status-detail: >
  Resolved 2026-09-10 by Opus on Kevin's "run everything with your taste".
  Option A: no tab row, HOME is the app, drill-downs stay on the meter rows
  that already do it. The Ask panel gets its own route reached from a HOME
  row rather than being buried in Settings or bloating the glance. Build
  ticket is 02, then 03b deletes.
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---

# One tab or none

**Kevin, 2026-09-10:** *"thinking of retiring meters page. just everything on home page (rename it
from calendar)"*

*"Thinking of"* is the tentative half of that sentence and this ticket exists because of it.

## The thing that has to be decided

`LegionRoute.TOP_LEVEL` is `listOf(CALENDAR, METERS)` (`ui/LegionRoute.kt:322`). Retire METERS and
the list has one entry. `LegionTabRow` (`ui/MainActivity.kt:1123`) then draws a row containing a
single always-selected label, which is not a navigation control - it is a heading that costs a row of
vertical space and does nothing when tapped.

**Nothing downstream can be built until this is settled**, because it decides where ticket 02's
rescued panels go and whether ticket 03 deletes a tab row or just a destination.

## The options

**A. No tab row. HOME is the app; everything else is a drill-down.**
`TOP_LEVEL` becomes empty or is deleted, `LegionTabRow` stops being rendered, and the drill-downs
(`money`, `body`, `fleet`, `pantry`, `checklists`) are reached the way Meters already reaches them -
by tapping the row that summarises them. `StatusLine` keeps the `SETUP` stamp. Back is the only way
up, and the `NavHost` destinations are all still registered, so every deep link, every
`ReminderAlarmReceiver` target and `EXTRA_ROUTE` keeps working untouched.

Recommended. It is the plain reading of *"just everything on home page"*, it deletes UI rather than
inventing it, and `topLevelOf` already exists to answer "which tab is lit" - with one surface the
question stops being asked.

**B. Keep two tabs, HOME and one other.** Meters is retired but something takes the second slot. This
is only worth doing if there is a real second surface, and the only candidate Kevin has named is the
news feed (ticket 07). That would make the shell HOME | NEWS. Defensible, but it decides ticket 06/07
by the back door - a feed gets a tab before anyone has decided what a feed is allowed to keep.

**C. Keep Meters.** The *"thinking of"* option. Home absorbs what Kevin wants at a glance and Meters
stays as the tap-through index. Costs nothing, changes nothing, and leaves the duplication he is
reacting to.

## What the decision must also state

1. **Where the Ask panel lands** (ticket 02). It cannot simply be dropped; ADR 0035 makes
   `show_generated_view` voice-only without it. On a single-surface HOME it is either a pane far down
   the scroll or a Settings row. It is a power-user affordance, not a glance affordance, so a
   Settings row is not obviously wrong - but it IS a demotion and should be chosen, not defaulted
   into.
2. **Whether HOME scrolls to everything or stays a day view with the meters folded under it.**
   `CalendarScreen` is 917 lines and `MetersScreen` is 935. A naive concatenation is a 1850-line
   screen and a very long scroll. Some pane order and some collapsing is implied by "everything on
   home page" and is not free.
3. **What happens to the drill-down entry points if the tab row goes.** Under option A they live in
   the meter rows themselves. That is already how Meters works, so this is inheritance rather than
   design - but it must be stated so ticket 02 has an address to move things to.

## Not in scope

The pane ORDER and visual treatment. That is ticket 02's build detail under the mission-control
vocabulary that already exists, not a decision for this ticket.

## Resolution

Kevin picks A, B or C, and answers the three questions above. Then 02 becomes buildable.


---

## Resolution, 2026-09-10

**Kevin, 2026-09-10: *"run everything with your taste"*** - the three open decisions on this map
delegated. This is that call, with the reasoning written out so it can be overturned cheaply.

### Option A. No tab row.

`TOP_LEVEL` empties, `LegionTabRow` stops being rendered, HOME is what the app opens to and the only
top-level surface. `StatusLine` keeps the SETUP stamp. The drill-downs stay registered `NavHost`
destinations reached by tapping the row that summarises them - which is what METERS already does, so
this is inheritance, not new design.

**Why, in one line: it is the only option that removes something.** *"Just everything on home page"*
is a request to stop having places, and B invents a new one while C keeps the one he is reacting to.

Three supporting reasons:

1. **A two-tab row was never load-bearing.** It arrived on 2026-09-01, briefly held three, and lost
   SETTINGS within hours because a second route to the same place read as duplication. That is the
   same complaint being made again, one level up.
2. **It costs 56dp of vertical space** (`TAB_ROW_HEIGHT`) on every screen, to switch between two
   things, one of which is being deleted.
3. **Nothing else depends on it.** `topLevelOf` and `label` have exactly one production caller
   between them - `LegionTabRow` itself. When it goes they are dead code and go with it, which
   `LegionRoute.kt` has done twice before and documented both times.

### The three questions this ticket said the decision must also answer

**1. Where the Ask panel lands: its own route, `ask/`, reached from a row on HOME.**

Not a Settings row and not a pane on HOME, and both alternatives were live.

- **Not Settings.** ADR 0035's second reason is that a voice-only capability is *invisible* - nobody
  can discover it. Settings is where you go to configure the app, not to use it. Filing a capability
  there satisfies 0035's letter while re-creating the problem it exists to solve.
- **Not a pane on HOME.** It is five pickers and a result. On a surface whose whole point is a
  glance, it is the single largest thing competing with the day, and it is the least frequently
  used. *"Everything on home page"* is about where you LAND, not a demand that every control be
  in one scroll.
- **Its own route** keeps HOME short, keeps the capability discoverable from HOME, and gives it a
  destination a deep link and a test can both name. It also means the ASK capability stops being
  welded to a screen that is being deleted, which is the failure this whole ticket exists to
  prevent.

**2. HOME's structure: the day first, the meters folded under it, collapsed when they have nothing
to say.**

Order, top to bottom:

| Band | What | Note |
|---|---|---|
| The day | Schedule, yet-to-do / done, recorded, checklist sections | `CalendarScreen`'s day view, unchanged |
| Needs you | Breaches only | Already conditional - absent entirely when nothing breaches. Keep that |
| The meters | Body, Money, Fleet, Lists, Recordings | Each one row, each taps through |
| The world | Weather line, area, newsletters | |
| Rows | ASK, and the media mini-bar | |

**A pane with nothing to say renders nothing, not an empty pane.** "Needs you" already works this
way and it is the right precedent: a stack of panes each saying "no data" is worse than the tab row
being removed. This is the one place where "everything on home page" needs a limit, and the limit is
that emptiness is not content.

The month grid stays above the day view as it is now. That is the 2026-09-01 shape
(*"month grid primary"*) and nothing in Kevin's 2026-09-10 message reopens it.

**3. The drill-downs keep their entry points on the meter rows.** Unchanged from METERS. Every
`onOpen*` callback that `MetersScreen` takes moves to HOME with the row that calls it. No route is
deleted - deleting one would break `EXTRA_ROUTE` deep links, and `LegionRoute.kt:837` already records
that reasoning for `FLEET_TELEMETRY`.

### What this does NOT decide

The visual treatment of the folded meter rows, which is ticket 02's build detail under the
mission-control vocabulary that already exists. And the news feed's address: under option A it is a
row on HOME like ASK, but whether it exists at all is ticket 06.

### Consequence to watch

**HOME becomes a long scroll and that is the risk this option accepts.** `CalendarScreen` is 917
lines and `MetersScreen` is 935. The collapse rule above is what keeps it honest, and ticket 08's
device pass is where it is judged - on the phone, not in a diff. If it reads as a wall, the answer is
more collapsing, not a second tab.
