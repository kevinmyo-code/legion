---
map: matrix-open
ticket: "03"
title: "Build: Matrix lists and to-dos"
type: build
status: open
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
