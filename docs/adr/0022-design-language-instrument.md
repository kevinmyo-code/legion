---
status: superseded
decided: 2026-08-01
decided-by: Kevin
superseded-by: [0023-design-language-mission-control]
source: "decisions.md 2026-08-01"
tags: [adr]
---

# 22. Design language: Instrument on Material 3's machinery

## Standing

SUPERSEDED by [[0023-design-language-mission-control]] on 2026-08-14, via the cyberdeck-ui effort in between. Kept because its token-layer decisions survived the visual change.

## Context

City-pop died and left nothing. Three directions were mocked against real screens: a dense ledger list, home, and a pantry item carrying macro estimates.

## Decision

Instrument, a dark readout with mono numerals and hairlines, built on Material 3's token layer rather than from scratch. Three overrides: shape scale flattened to near zero, monospace for numerals, one accent with secondary and tertiary as neutrals. Dynamic colour declined.

## Consequences

- Money and provenance roles live outside `ColorScheme` in `LegionSemantics`, because M3 has no slot for them. That structure survived into mission-control.
- `debit` resolves to plain `onSurface`. Most rows are debits, and colouring them all red is noise, not signal.
- The five previews in `ui/theme/ThemePreview.kt` were never rendered before screens were built on the theme. That is the origin of lesson L11.
