---
map: one-home
ticket: "01"
title: "One tab or none: what the shell is when METERS is gone"
type: decision
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
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
