---
map: aspect-engine
ticket: "09"
title: "Dashboard grid mechanics: staged prototype"
type: prototype
status: open
status-detail: ""
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
