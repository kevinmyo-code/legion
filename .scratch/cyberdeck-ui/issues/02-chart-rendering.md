---
map: cyberdeck-ui
ticket: 02
title: "Chart rendering: library or hand-rolled Canvas"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Chart rendering: library or hand-rolled Canvas

## Question

How should LEGION draw charts in Compose? Nothing in the repo charts today. Surfaces will need at
minimum: line/area trends over time (weight, sleep, calorie intake, spend), bar comparisons
(budget vs actual per category, sessions per week), and possibly sparklines in rows.

Compare: Vico, other maintained Compose chart libraries, and hand-rolled Canvas/DrawScope.

Constraints from CLAUDE.md and the map:
- Clone-and-run: a normal Gradle dependency is fine; anything needing accounts/keys/runtime
  fetching is not. Assets bundled.
- Dark-only, and the deck aesthetic will demand heavy styling control (custom grid lines, glow,
  mono axis labels) - a library that fights restyling loses to Canvas.
- Money is Long cents; charts must not round through Double where it changes a displayed value.
- Ambient motion is wanted (map Notes) - animated draw-in matters.
- APK size and dependency weight matter to a solo phone app.

Deliverable: recommendation with the tradeoff table, written to
`.scratch/cyberdeck-ui/research/chart-rendering.md`.

## Answer

**Hand-rolled Canvas/DrawScope; no chart dependency.** Resolved by research subagent, 2026-08-07;
full tradeoff table at [research/chart-rendering.md](../research/chart-rendering.md).

1. The cyberdeck aesthetic makes every library default a thing to override; the repo already
   proves the hand-rolled pattern (`TelemetryChart` in `ui/fleet/TelemetryRows.kt`, ~35 lines,
   `verified`), and Long-cents label exactness is trivial when Float only ever touches pixel
   geometry.
2. Library friction is real: KoalaPlot's latest needs Compose 1.10/Kotlin 2.3 vs the repo's BOM
   2024.05.00/Kotlin 2.1.0 (`traced`); current Vico likely also forces a BOM bump (`reasoned`);
   ComposeCharts is pre-1.0.
3. Estimated cost ~350-550 lines: `DeckLineChart`/`DeckBarChart`/`DeckSparkline` + axis and glow
   helpers (`reasoned`). Escape hatch: if pan/zoom/markers are ever needed, adopt Vico then
   (actively maintained, v2.5.2 June 2026, Maven Central) and bump the BOM in that ticket.
