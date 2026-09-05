---
map: architecture
ticket: "04"
title: "Calendar, ledger, pantry: a ViewModel each, controllers injected"
type: build
status: open
status-detail: ""
blockers: []
blocked-by: []
open-blockers: 0
ready: true
tags: [ticket]
---

# Calendar, ledger, pantry: a ViewModel each, controllers injected

The three screens where a screen-local loader re-runs on rotation, process death and every Realtime
push. Each gets a `@HiltViewModel` exposing one `StateFlow<UiState>`, collected with
`collectAsStateWithLifecycle`. The controllers those screens call convert from `object` to
`@Inject constructor` classes; their old static call sites elsewhere go through the ticket 02 shim
until their own screen converts.

`CalendarScreen` is first: it carries five independent loaders (events, tasks, recordings,
checklists, month todo counts) and a failure-signal discipline for each. One `UiState` with one
`Failed` per source is the same honesty with less duplication.

Verify: suite green, the three screens behave identically on the A25 including rotation mid-load,
and every "Couldn't load ..." sentence still renders for a forced failure.
