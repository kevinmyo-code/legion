---
map: drive-ui
ticket: 08
title: "Layout for a phone that is 384 x 832, not a head unit"
type: prototype
status: open
status-detail: "Q26/Q27/Q28 built (8123f4e, 8386ec5); Q29 portrait lock and ticket 05's post-drive summary still open"
blockers: ["04", "05"]
blocked-by: ["[[04-gauge-design]]", "[[05-trip-content]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Layout for a phone that is 384 x 832, not a head unit

## Question

On the A25, roughly a third of the driving screen is empty - everything sits in the top half, then a
void, then EXIT pinned at the bottom. Verified by screenshot 2026-08-16.

Blocked by [gauge design](04-gauge-design.md) and [trip content](05-trip-content.md) because there is
nothing to lay out until those decide what exists.

1. **The dead third.** Trip content is the obvious tenant, but confirm that rather than assuming -
   deliberate empty space on a glanceable screen is a legitimate choice, and cramming it is the easy
   wrong answer.
2. **384 x 832 dp, measured.** Every layout figure in `.scratch/mission-control/` was measured
   against the retired A17k at 360 x 806 (`MEMORY.md`). Nothing here inherits those numbers without
   re-measuring.
3. **Reach.** A phone in a mount is glanced at and rarely touched; a phone in a hand is touched.
   EXIT is a 72dp key at the very bottom, which is the easiest place to reach one-handed and the
   worst place to avoid hitting by accident. Decide whether EXIT wants a confirm, given it is the
   one control on the screen.
4. **The Alfred strip.** It sits mid-screen today between the pods and the void. Whether the talk
   affordance belongs there, or at thumb level, is a reach question too.
5. **What the shell already does.** `MainActivity` strips its chrome for this route
   (`MainActivity.kt:299`, `:369`, `:398`) while still respecting insets. Any layout works inside
   that, and does not re-solve it.

## Answer (settled, pending the prototype)

**Stark's recommendations, put to Kevin 2026-08-16, unopposed.**

- **Q26 - fill the dead third with trip content.** A third of a glance screen earning nothing is
  waste rather than restraint. Confirmed as the tenant by
  [trip content](05-trip-content.md)'s three figures.
- **Q27 - EXIT keeps no confirm dialog.** It is the only control on the screen; making it annoying
  is worse than an occasional stray exit.
- **Q28 - the Alfred strip moves to thumb level**, just above EXIT, rather than sitting mid-screen
  between the pods and the void.
- **Q29 - portrait only.** Landscape stays in the fog until a mount actually exists.
- **384 x 832 dp is re-measured, not inherited.** Every figure in `.scratch/mission-control/` was
  measured against the retired A17k at 360 x 806.

**Still open:** the concrete layout, which lands with [the gauge prototype](04-gauge-design.md).
Kevin's reference direction is dense-and-labelled rather than minimal, so the layout question is
now "what earns a place on a busy instrument panel", not "how do we fill a void".

## Verification 2026-08-16 - PARTIALLY BUILT, and the blocker is now stale

Swept against `ui/DrivingModeScreen.kt` after commit `1ff4807`. All `traced`.

**Met:** Q27 EXIT keeps no confirm (`:453-466`, bordered 72dp key straight to `onExit`); Q28 the
Alfred strip sits at thumb level directly above EXIT (`:443-445`).

**NOT met - Q26, the dead third:** `TripBlock` (`:827-843`) hardcodes `val tracking = false` and
renders one `"TRIP // NOT TRACKING"` line. **`TripStat` (`:846`) is unreachable dead code behind
that constant** - the sixth orphan found today. None of trip content's three figures render.

**The stated blocker is STALE, and it went stale inside the same session that wrote it.** The doc at
`:805-810` says no drive-boundary object exists. Commit `61a62b0` - **three commits later the same
evening** - added the `drives` table at Room v23 with `startedAt`/`endedAt`/`miles`/`gallons`/
`endReason`, plus `data/local/Drive.kt` and `DriveDao.kt`. `TelemetryRecorder` writes it;
`DrivingModeScreen` never reads it, still calling only `odbSampleDao().getLatest` (`:234-236`).

**So Q26 is unblocked and this ticket is takeable now.** It needs the screen to read `drives` and
flip that boolean, plus the comment corrected.

**Q29 portrait-only is NOT enforced:** `AndroidManifest.xml:106` is still
`screenOrientation="unspecified"` and the screen never touches orientation. Arguably a scoping call
rather than a build item, but it is not true in code today.

**Also outstanding from trip content:** no post-drive summary on exit - `onExit` is a bare
navigation callback.

## 2026-08-18 verification - mostly BUILT, and this ticket's own earlier note is stale

Re-read against the tree. The screen was rebuilt in `8123f4e` and the trip block given real numbers
in `8386ec5`, both 2026-08-16. **The two SHAs this ticket cited above, `1ff4807` and `61a62b0`, do
not exist in this repo's history** - treat the citations, not the code, as the error.

| Item | State | Evidence |
|---|---|---|
| Q26 the dead third takes trip content | BUILT, narrower than ticket 05 asked | `DrivingModeScreen.kt:442`, `:863-887`. The hardcoded `val tracking = false` is gone; `TripStat` is reachable |
| Q27 EXIT keeps no confirm | BUILT | `:469-482`, straight `.clickable(onExit)`, no dialog |
| Q28 Alfred strip at thumb level | BUILT | `:459-461`, between the instrument column and EXIT |
| 384x832 re-measured, not inherited | BUILT at preview level | all four previews `widthDp = 384, heightDp = 832` (`:970`, `:999`, `:1020`, `:1042`) |
| Q29 portrait only | **NOT BUILT** | `AndroidManifest.xml:106` is still `screenOrientation="unspecified"` |

Two honest deviations, neither silent:

- Ticket 05 Q13 asked for **three** figures for the drive **in progress**; what shipped is **two**
  (ELAPSED, DISTANCE) for the **last finished** drive, labelled `LAST DRIVE · <age>`. MPG is
  deliberately withheld (`ui/DrivingDialMath.kt:140-142`, `MpgTrust.SHOW_MPG = false`, ticket 09).
  Documented at `DrivingModeScreen.kt:832-846` and in `8386ec5`'s commit body.
- Ticket 05 Q16's **post-drive summary on exit is NOT built** - `MainActivity.kt:525` is a bare
  `popBackStack()`.

**Not screenshot-verified.** The dead third is gone *structurally* (`reasoned` from the layout: the
content column and the segment Row both take `weight(1f)`, and fader/trip/footer/Alfred/EXIT fill
below), but nobody has run it on the A25 since the rebuild.

All rows above `traced`.

