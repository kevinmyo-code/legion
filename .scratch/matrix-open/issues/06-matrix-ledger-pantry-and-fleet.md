---
map: matrix-open
ticket: "06"
title: "Build: Matrix ledger, pantry and fleet"
type: build
status: open
blockers: []
tags: [ticket]
---

# Build: Matrix ledger, pantry and fleet

Lookups first: ledger balances and recent transactions, pantry stock and recent purchases, fleet cars and next maintenance. Text rows only. Every UNRECONCILED row and every estimate said in words on its row (CLAUDE.md section 4 rule 7, rule 5; never collapsed). Same controllers as the Soft screens.

**Style (Kevin, 2026-10-24): text-only, like Matrix HOME.** Green monospace on black from
`ui/dumb/MatrixScheme.kt`, rows you tap, no cards, icons, images or charts, no rain. A new screen, not
a recolour of the Soft one. Reached from Matrix HOME while the mode is on; the Soft screen is unchanged
when it is off. Every action also says its failure in words.

Verify: ViewModel / UiState tests for the screen's states (loading, empty, unreadable said apart from
empty, error). On the A25: open each from Matrix HOME and do one write.
