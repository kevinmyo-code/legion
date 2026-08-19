---
map: cyberdeck-ui
ticket: 18
title: "Build: FLEET rebuild"
type: task
status: resolved
status-detail: ""
blockers: ["13", "14"]
blocked-by: ["[[13-build-shell]]", "[[14-build-chart-kit]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Build: FLEET rebuild

## Question

Rebuild per ticket 09: merge FleetScreen + TelemetryScreen into one FLEET module. UPLINK panel
(live/stale, worded relativeAge, telemetry-chart drilldown), MAINTENANCE (due horizon, amber
advisories, schedule + history drilldown), DRIVES (MPG sparkline, history drilldown), CARS row
(active vehicle named, places reachable). Remove the TELEMETRY route or alias it into the
drilldown; check MainActivity route wiring and any notification deep-links into the old routes.

## Answer

Built 2026-08-08 (coding agent, worktree, merged). One FLEET module: UPLINK leads always (live
or worded-stale via relativeAge; FAULTS folded into UPLINK content - accepted design call, the
four-panel list did not name faults and inventing a fifth panel would have been worse),
MAINTENANCE with amber advisories + drilldown, DRIVES (DailyDriveLog rollups, avgMpg null = gap
not zero), CARS row naming the active vehicle. Telemetry content became UPLINK's in-screen
drilldown; FLEET_TELEMETRY route kept wired to the unchanged TelemetryScreen (no live deep-link
caller, traced). DRIVE MODE tag stubbed inert on UPLINK as ticket 20's entry point. compile +
tests green (tested). Deferred: unit tests for the two new pure helpers; on-device QA to 21.
