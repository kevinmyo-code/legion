# The maintenance surface, rebuilt

Type: prototype
Status: resolved (2026-08-15)
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
4. `Recaps` row -> recaps drilldown, with an inline miles sparkline
5. `Oil Analyses` row -> oil-analysis drilldown

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

---

## Answer (2026-08-15)

**Three surfaces, not one.** Kevin: triage screen, full schedule behind it, and a row taps into its
own detail screen.

```
FLEET tile  ->  MAINTENANCE (triage)  ->  FULL SCHEDULE  ->  ITEM DETAIL
                     what needs doing      every item        one item, editable
```

### 1. MAINTENANCE - triage, and it stays glanceable

Overdue first, then upcoming. **What it must NOT do is what it does today**: silently drop the
seven of Kevin's ten items that have no anchor (`buildDueRows` filters `isUnknown` at
`FleetRows.kt:120`).

They do not belong in a due list - they genuinely are not due, and `VehicleController.unknownItems`
exists unused for exactly this. So they are **counted, not listed**:

> `7 items with no history - see full schedule`

One line, tappable, straight into the full schedule filtered to them. **They stop being invisible
without pretending to be urgent**, and that line is the natural prompt for backfill.

### 2. FULL SCHEDULE - the complete, editable inventory

Every item including the unknowns and the `[GUESS]`-tagged ones (ticket 06). Add, and the
confirm-all flow, live here. Deleted (tombstoned) items do not appear.

### 3. ITEM DETAIL - one item, and where every action lives

Interval (editable), anchor (the three-way picker from ticket 07), the `[GUESS]` tag and its
confirm, every `ServiceRecord` matching this item, and delete.

**This is what makes the density work.** Mission-control ticket 03: a dense feed row cannot be
tapped, and a tappable row costs 48dp against a 560dp content budget - about 11 rows a screen.
Putting edit/log/add/delete inline on every row would have blown that. A detail screen keeps the
lists dense and gives per-item history a home, answering questions 5 and 6 with one shape.

### The tile must stop saying OK

`buildFleetTile` reads `"OK"` when nothing is overdue. On Kevin's phone it currently says
**`OK / NEXT BRAKE FLUID -`** while seven items are unknown and the next-up row is an orphan with no
interval. That is not true and it is the surface he sees most.

- Hero: the overdue count, or `OK` **only when nothing is overdue and nothing is unknown**.
- Otherwise the caption carries the unknowns: `"3 due - 7 unknown"`.
- Seven characters of hero (mission-control ticket 05) - `"3 DUE"` fits, and the caption does the rest.

### Miles-or-months, on screen

Kevin ruled due = whichever comes first. A row shows **the axis that is closer to due**, not
whichever is non-null, and the sub-line names it: `"every 7,500 mi or 6 mo - due in 1,100 mi"`.

**`dueFraction`'s month-is-30-days approximation stops being cosmetic here** and must be fixed: once
months can drive due-ness, a 6-month interval computed as 180 days drifts almost 6 days a year
against a real calendar.

### The two dead ghost lines

`"N service records on file, no screen yet"` becomes ticket 11's screen. The build-sheet line is
**out of scope for this map** - remove it rather than leave a dead pointer on a rebuilt surface.

### Method and verification

Prototype as a claude.ai artifact before Compose - the mechanism that worked for every
mission-control decision. Mission-control's visual language is settled and reused, not reopened:
`DeckPane`, `DeckRow`, `DeckMeter`, `DeckTag`, the tiling grammar.

1. On the device with Kevin's real schedule: **all ten of the Jeep's items reachable**, the seven
   unknowns counted on triage and listed in full schedule, `[GUESS]` on every one with an interval.
2. The tile does not say `OK` while unknowns exist.
3. Every action reachable from a row tap; **confirm the drilldown-return path refreshes the parent**
   (ticket 04's stale-parent bug - press BACK, do not switch tabs).
4. Row count against the 560dp budget, measured not assumed.
5. **Compose previews have never rendered on this project.** Installing and looking is the
   substitute, and it has caught every real bug so far.

### Assumptions ledger

- `traced`: `buildDueRows`' `isUnknown` filter; `unknownItems` being unused; `buildFleetTile`'s
  strings; `dueFraction`'s 30-day month; mission-control's 48dp/560dp budget.
- `on-device`: the tile reading `OK / NEXT BRAKE FLUID -` on 2026-08-15 with seven unknowns.
- `reasoned`: that ~11 rows per screen follows from 48dp against 560dp; not measured on the device.
- **Not built. Unblocks 11.**

