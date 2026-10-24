---
map: matrix-open
ticket: "03"
title: "Build: Matrix lists and to-dos"
type: build
status: built
status-detail: "Built 2026-10-24, suite green. Owed on the A25: To-dos on Matrix HOME opens the text lists screen; open a list, tick and untick an item, add one, delete one; a measured item takes a typed value; with the engine unreachable a tick reads Not synced yet; the LEGION row opens the index and Lists opens from it; Back steps out of the list, then to HOME; the Soft Lists screen is unchanged with Matrix off."
blockers: []
tags: [ticket]
---

# Build: Matrix lists and to-dos

Checklists, shopping lists: list of lists, open one, tick / untick, add an item, delete an item. Same controllers as the Soft screens (ADR 0035 spirit: one controller, two faces).

**Style (Kevin, 2026-10-24): text-only, like Matrix HOME.** Green monospace on black from
`ui/dumb/MatrixScheme.kt`, rows you tap, no cards, icons, images or charts, no rain. A new screen, not
a recolour of the Soft one. Reached from Matrix HOME while the mode is on; the Soft screen is unchanged
when it is off. Every action also says its failure in words.

Verify: ViewModel / UiState tests for the screen's states (loading, empty, unreadable said apart from
empty, error). On the A25: open each from Matrix HOME and do one write.

## What was decided while building

Orchestrator decisions (2026-10-24), not Kevin's:

- **Navigation.** Matrix HOME's To-dos row opens the new screen. One new HOME row, "LEGION", after All
  apps, opens a text index of the Matrix screens (Lists, Calendar, Brief, Notes, Voice notes, Ledger,
  Pantry, Fleet), added to the index as each ticket lands. No row per aspect. The screens are not
  nav-graph routes: HOME keeps a stack (`MatrixDest`) so Back steps out one level, and nothing is
  reachable while Matrix mode is off.
- **One implementation of every write.** `MatrixListsScreen` drives the existing `ListsViewModel`, so
  tick, untick, add, delete and create are the same `ChecklistController` calls and a failed one is the
  same `writeError` sentence. `MatrixListsText` is the pure mapping from its state to words, and is what
  the tests cover.
- **Scope.** List of lists (routines and plain, archived behind a toggle), open a list, tick, untick,
  add, delete an item, create a plain list, a measured item takes a typed value. Left to the Soft
  screen: rename, schedule, archive, delete a list, history, reorder, edit an item.
- **Page-level unreadable.** `ListsViewModel` has no page-wide failure flag, so a list whose items cannot
  be read says "Couldn't load" on its own row (never "Empty"); a throw reading the list of lists itself
  is not caught by that ViewModel on either face.
