---
map: aspect-engine
ticket: "09"
title: "Dashboard grid mechanics: staged prototype"
type: prototype
status: claimed
status-detail: "Prototype built 2026-08-23 on branch prototype/dashboard-grid; awaiting Kevin on the A25."
blockers: ["08"]
blocked-by: ["[[08-widget-contract]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Dashboard grid mechanics: staged prototype

## Question

Charter decision 9 staged the mechanics: reorderable half/full-width cards first, free
launcher-style cell grid (drag, resize, reflow) second. Prototype to answer:

1. **Stage 1 feel.** A pager of pages, each a reorderable card column with half/full widths, in
   the mission-control skin. Does it feel like "my dashboard" or like a settings list? Kevin
   reacts to a running prototype on the A25, not a description.
2. **Stage 2 cost.** A spike of true 2D drag/resize in Compose (cell grid, drag targets, reflow,
   persistence): how much real work is it, and does any library help or is it hand-rolled? The
   answer prices stage 2; it does not have to ship it.
3. **Edit mode.** Long-press to enter jiggle/edit mode (add, move, resize, remove) vs always-live
   handles. Recommend after feeling both.
4. **Pager chrome.** Page indicator, page management (reorder pages, rename), and where "new
   aspect" lives (a + page at the end, like launchers?).

/prototype skill; throwaway branch; findings and Kevin's reactions are the answer.

## Findings

Built 2026-08-23 by a worktree agent, commit 7277be2 on branch `prototype/dashboard-grid`.
Resolution waits on Kevin's on-device reaction; this section is the mechanics half.

1. **Stage 1 exists and compiles clean.** Debug-only source set `app/src/debug/` (never in a
   release build): `PrototypeDashboardActivity`, a HorizontalPager of HOME + FLEET + LEDGER +
   a trailing + stub, page dots, reorderable half/full-width cards packed two-per-row, five
   widget mocks (stat tile, record list, next-due, quick-add, agenda), all on the existing
   Deck components with zero new theme tokens. Long-press enters a jiggle edit mode with drag
   handle, remove chip, and an EDIT/DONE pill.
2. **Install and react:** `adb install` the worktree APK
   (`.claude/worktrees/agent-a4382438f9b0c388f/app/build/outputs/apk/debug/app-debug.apk`), then
   `adb shell am start -n com.kevin.legion/.prototype.PrototypeDashboardActivity`. No launcher
   icon on purpose.
3. **Known rough edge, honest:** the reorder math collapses row and column into one nearest-centre
   distance, so a half-width card dragged sideways can jump rows instead of swapping across. This
   is exactly the boundary a real cell model fixes in stage 2.
4. **Stage 2 pricing:** no Compose library does 2D drag + resize + reflow (sh.calvin.reorderable
   is reorder-only; the older composereorderable is unmaintained). Hand-rolled occupancy-map model,
   closer to porting react-grid-layout's algorithm than wiring a dependency. Estimate 8-12 working
   days including persistence wiring and on-device QA (reasoned, not timed).
5. **Edit-mode verdict from building it:** always-live drag would crowd half-width cards with
   permanent chrome and make accidental drags the main failure; the modal edit mode also gives a
   natural commit point. Recommend keeping edit mode.
