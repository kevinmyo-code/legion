---
map: cyberdeck-ui
ticket: 14
title: "Build: deck chart kit"
type: task
status: resolved
status-detail: ""
blockers: ["12"]
blocked-by: ["[[12-build-theme]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: deck chart kit

## Question

Hand-rolled Canvas per ticket 02: `DeckSparkline` (panel-height trend, endpoint dot),
`DeckLineChart` and `DeckBarChart` (drilldown-height, mono axis labels, faint grid, draw-in
animation per ticket 04), plus the range-selector row (7d/30d/90d/all). Long cents and exact
values never round through Double for LABELS (Float only touches pixel geometry). Not-logged
periods render as GAPS, never zeros. Start from `TelemetryChart`'s pattern; unit-test the
label formatting and gap logic (pure functions, no Robolectric).

## Answer

Built 2026-08-08 (coding agent, isolated worktree, merged to feat/cyberdeck). DeckChartData.kt
(pure: DeckPoint/DeckBar/DeckRange, day bucketing via meals.dayStartEpoch with caller zone,
scales, centsLabel wrapping ledger.formatCents - no Double/Float in the label path),
DeckCharts.kt (DeckSparkline/DeckLineChart/DeckBarChart/DeckRangeSelector, one-shot draw-in,
snap when motion disabled, null = gap with muted underline marker for absent bar slots),
18 unit tests green (tested). Canvas visual correctness reasoned only - deferred to ticket 21.
