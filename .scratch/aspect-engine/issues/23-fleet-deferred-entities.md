---
map: aspect-engine
ticket: "23"
title: "Deferred fleet entities onto the engine"
type: task
status: open
status-detail: ""
blockers: ["22"]
blocked-by: ["[[22-cutover-per-aspect]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Deferred fleet entities onto the engine

## Question

Wave 4 migrated the core chain completely and deferred the rest by name
(docs/architecture/wave4-carve-2026-08-23.md): BuildEntry, VehicleSpec, OilAnalysis,
MonthlyRecap, DailyDriveLog, YearlyWrapped, Drive. Rule each (record type / plugin-internal /
dies), carve, seed, copy per the wave pattern - after fleet's cutover settles the ServiceHistory
shape they reference. Drives are summaries, not telemetry; OBD samples stay plugin-internal
permanently.
