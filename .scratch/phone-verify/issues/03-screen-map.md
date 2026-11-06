---
map: phone-verify
ticket: "03"
title: "docs/screen-map.md, generated, drift fails"
type: build
status: built
status-detail: "built 2026-11-05 on feat/phone-verify; drift tests green; in the commit hook"
blockers: []
blocked-by: []
tags: [ticket]
---
# docs/screen-map.md, generated, drift fails

`tools/screen_map.py` + `tools/screen_map_copy.py` (decision 4). For each screen: name, id, how to
reach it from launch by selector, the header `open` waits for, test tags, key controls, and the
voice tools that open it.

**Read from code:** route constants (`ui/LegionRoute.kt`), `composable(...)` registrations and their
`navController.navigate` edges (`ui/MainActivity.kt`, comments stripped so tombstones do not count),
the tap label beside `onClick = onOpenX` in the parent screen's file, `DeckScreenHeader(title = ...)`,
`testTag(...)`, manifest activities, `MatrixDest`, `FleetDrilldown`.

**Fails, naming the thing:** a registered route with no copy; copy for a route that is gone; a
manifest activity neither mapped nor ignored; a `MatrixDest` with no copy; a route with no way in; a
tap text no string literal in `app/src/main` has any more; a tag no `testTag` sets; a voice tool no
`*Toolbox.kt` declares; a parent that is not a screen; a stale doc.

Covers the screens the brief named: Memory (`settings/memory`), the ID vault (`vault`), Money
(`money`, `money/details`, `money/pantry`, `money/pantry/import`), Specs and the plate
(`fleet/specs`), Updates (`settings/updates`), Matrix HOME (`matrix/home`) and its screens.

Hooked into `.claude/settings.json`'s `git commit` hook beside `voice_guide.py`, staging
`docs/screen-map.md`; `guard_generated.py` blocks hand edits to it.

## Verification

- [x] `tools/tests/test_screen_map.py`: the real map agrees; each drift case planted into a copy of
      the copy module is named.
- [ ] Reach paths walked with `open --by-taps` on the phone: ticket 06.
