---
map: one-home
ticket: "03b"
title: "Delete MetersScreen once its orphans have somewhere to live"
type: build
status: open
status-detail: ""
blockers: ["01", "02"]
blocked-by: ["[[01-what-the-shell-is-without-meters]]", "[[02-rehome-the-orphans]]"]
open-blockers: 1
ready: false
tags: [ticket]
---

# Delete MetersScreen

Split from [[03-calendar-becomes-home]] on 2026-09-10. The rename went ahead because it was decided;
this did not, because *"thinking of retiring meters page"* is not a ruling and because deleting the
file without [[02-rehome-the-orphans]] would take `show_generated_view`'s only hands path with it
(ADR 0035).

**Do nothing here until ticket 01 says METERS is retired and ticket 02 is green.**

## The deletion, if ticket 01 says so

Only after ticket 02 is green:

- Delete `ui/MetersScreen.kt` and `LegionRoute.METERS`.
- Remove the `composable(LegionRoute.METERS)` registration (`MainActivity.kt:755`) and the
  `onOpen*` callback wiring that fed it.
- `ui/MetersScreenTest.kt`: the 14 tests moved in ticket 02. Whatever remains that tested the SCREEN
  rather than the functions goes with the screen. **Say how many tests were deleted and why, in the
  report.** A suite that shrinks silently is a suite nobody can audit.
- The drill-down destinations (`money`, `body`, `fleet`, `pantry`, `checklists`) stay registered.
  They were already not tabs. Deleting them is not in this ticket and would break deep links.

## Verification

- `compileDebugKotlin`, full suite, totals from the JUnit XML under `app/build/test-results/`.
- `python tools/docs_check.py` clean - `docs/architecture/` names source paths, and a deleted file is
  exactly what it fails on.
- `python tools/voice_guide.py` exits zero.
- The test-count accounting above, in the report, in numbers.
- Device steps belong to [[08-ship-pass]].
