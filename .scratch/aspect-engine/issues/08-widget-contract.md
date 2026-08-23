---
map: aspect-engine
ticket: "08"
title: "The widget contract"
type: grilling
status: open
status-detail: ""
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 1
ready: false
tags: [ticket]
---
# The widget contract

## Question

Every aspect page and home is a grid of widgets (charter decision 9). Specify the contract:

1. **Engine widget types v1.** Proposed: stat tile (computed field or count), record list (top N
   by filter/sort), next-due, quick-add button, single-record card. What else earns v1? What is
   deliberately v2 (charts?)?
2. **Widget config schema.** A widget instance = type + aspect/recordType + parameters + size +
   position + page. Stored where - its own engine table (it is user layout state, synced or
   per-device?).
3. **Native plugin widgets.** Now-playing, OBD live gauges, weather: same contract, rendered by
   plugin composables. What does the contract require of them (size classes, tap target, error
   state in words)?
4. **Default arrangements.** Mission-control's current home and aspect screens become the shipped
   default layouts. Enumerate which existing screens map to which widget arrangement, and which
   have no widget equivalent yet (that list feeds ticket 14).
5. **Empty and error states.** A widget over a deleted aspect, an empty record type, an
   unreadable source: words, not blanks (the unreadable-vs-empty rule).
