# The maintenance surface, rebuilt

Type: prototype
Status: open
Blocked by: 05, 06   # both resolved 2026-08-15 - UNBLOCKED

## Question

Kevin on the current drilldown: **wrong things, wrong shape, read-only, and too shallow.** All four.
So this is a rebuild, not a revision.

## What is there now

`MaintenanceDrilldownScreen` (`ui/fleet/FleetDrilldowns.kt:61-195`), reached by tapping the
MAINTENANCE half-tile on FLEET (`ui/FleetScreen.kt:442-447`). Top to bottom:

1. `< BACK`, title `MAINTENANCE`, hairline
2. A flat `LazyColumn` of due rows - `DeckRow(label, value)` plus a sub-line reading
   `"every 3,000 mi - last at 132,400"`. **None of these rows is clickable.**
3. Two dead ghost lines: `"2 service records on file, no screen yet"` and
   `"N build sheet entries on file, no screen yet"` - **not tappable**
4. `Recaps` row → recaps drilldown, with an inline miles sparkline
5. `Oil Analyses` row → oil-analysis drilldown

Three interactions exist on the entire surface: open the drilldown, open recaps, open oil.

**`buildDueRows` drops every unknown-anchor item before rendering** (`ui/fleet/FleetRows.kt:120`).
So items whose history LEGION does not know are **invisible here**, not merely unsorted -
`VehicleController.unknownItems` exists (`:574`) and this screen never calls it. That is very
likely a large part of "shows the wrong things": the schedule Kevin thinks he is looking at may be
a filtered subset of the schedule that exists.

`DueRowView.fraction` is computed by `dueFraction` (`:179-195`) and **rendered nowhere** - a meter
was designed and never wired. Known nit carried from `quant-viz`: `dueFraction` treats a month as
30 days.

## What has to be decided

1. **What the surface is for.** "What needs doing" and "what is the full schedule" are different
   screens wearing one name. Overdue-first triage vs. a complete editable inventory. Decide whether
   that is one surface with sections, two surfaces, or a root plus drilldown.
2. **Where the unknown-anchor items go.** They cannot stay invisible. As their own section? Mixed
   in with a worded state? They are also **the natural prompt for backfill** - an item LEGION knows
   nothing about is exactly the one to ask Kevin about.
3. **Miles-or-months, on screen.** Kevin ruled due = whichever comes first. Today the sub-line picks
   one axis. A row now has to express two clocks without becoming unreadable, and `dueFraction`
   has to compute against both - **including the month-is-30-days nit, which stops being cosmetic
   once months can drive due-ness.**
4. **The guess label.** Ticket 06 decides the words; this ticket decides where they sit in the row
   without wrecking the density. Mission-control's grammar: 22dp display rows, 48dp tappable rows,
   and **a dense feed row cannot be tapped** (`mission-control/issues/03`). Every row here becomes
   tappable, so every row costs 48dp. **Count the rows against the 560dp content budget** before
   committing to a layout.
5. **The actions, and where they live.** Edit an interval, log a service, add an item, delete an
   item, set the odometer. Row-level tap into a detail sheet, inline affordances, or a mode? Kevin's
   "read-only, can't act" complaint is the loudest of the four.
6. **Depth: per-item history.** "Too shallow / not enough history." An item's own past - every
   service record matching it, the interval as it changed over time - is a per-item detail view.
   Decide whether that is where editing lives too, which would collapse (5) and (6) into one answer.
7. **The half-tile hero.** `buildFleetTile` (`ui/TodayGapResolvers.kt:333-342`) currently reads
   `"N DUE"` / `"OK"` / `"NO LINK"`. Seven characters of hero, per mission-control ticket 05. Does
   it change, and does it still tell the truth once unknown items are counted?
8. **The two dead ghost lines.** One becomes ticket 11's screen. The other (build sheet) is out of
   scope for this map - decide whether it stays as a dead line or goes.

## Method

Prototype it as a claude.ai artifact before writing Compose - that mechanism worked for every
mission-control design decision. Mission-control's visual language is **settled and not reopened**:
this reuses `DeckPane`, `DeckRow`, `DeckMeter`, `DeckTag`, the mono face and the tiling grammar. The
prototype is about information and action, not about looks.

## Verification

On the device, with Kevin's real schedule. **Compose previews have never been rendered on this
project, any screen, ever** - installing and looking at it is the substitute, and it is the one that
has caught every real bug so far.
