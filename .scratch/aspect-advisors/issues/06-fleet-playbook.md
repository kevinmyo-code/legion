---
map: aspect-advisors
ticket: 06
title: "Research: the FLEET maintenance playbook"
type: research
status: resolved
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: false
tags: [ticket]
---
# Research: the FLEET maintenance playbook

## Question

Assemble the maintenance-advisory framework the FLEET advisor ships with: generic service-interval
best practice by mileage and time (oil, filters, brakes, tires, fluids, battery, belts), severity
triage for common OBD-II DTC families (drive-on vs check-soon vs stop), seasonal/storage care, and
cost-sanity heuristics (what is normally DIY vs shop). The advisor also sees LEGION's
MaintenanceItem targets and OBD history, so the playbook should say how to reason from logged
miles/dates rather than assumptions. Sources licensing-clean to paraphrase (no verbatim OEM manual
copying). Deliverable: distilled playbook draft in `research/fleet-playbook.md` sized for a
SubAgent brief, with a sources list.

## Answer

Playbook drafted: [research/fleet-playbook.md](../research/fleet-playbook.md), ~170 lines,
written as direct SubAgent instructions. Six sections: role limits (advisor not mechanic,
estimates-in-words per §7), log-first reasoning (dual-axis due, `neverDone` = overdue vs
null-anchors = unknown-never-due, mirroring MaintenanceItem.kt semantics), a generic
interval table by mileage AND time (17 items, manual-wins caveat throughout), three-tier
DTC triage (stop-now / check-soon / drive-on, flashing MIL as the hard stop signal),
seasonal + storage care, DIY-vs-shop cost sanity, and explicit hard deferrals (manual,
mechanic inspection, NHTSA recall lookup).

Assumptions ledger:
- researched: NHTSA tire rules (2/32, monthly pressure, 6-10 yr aging), AAA cadences
  (oil ~5k, rotation/brake 6-8k, brake fluid ~2 yr), flashing-MIL stop rule + catalyst
  risk, DTC family severities (P0300 serious / P0420 + P0171 moderate / P0442 low) and
  their causal chains, storage prep (stabilizer, tender, flat-spots), 2026 labor-rate
  ballparks.
- traced: MaintenanceItem field semantics (neverDone vs unknown anchors, dual intervals)
  read from `data/local/MaintenanceItem.kt`.
- reasoned: the priority ordering (safety > engine-protection > comfort), the interval
  table's consolidation across sources, and mapping less-covered items (PS fluid, fuel
  filter, diff fluid) to common ranges - conventional wisdom, not single-source cites.
