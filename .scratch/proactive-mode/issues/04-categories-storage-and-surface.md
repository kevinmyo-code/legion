---
map: proactive-mode
ticket: 04
title: Five switches that actually switch something
type: grilling
status: open
status-detail: ""
blockers: ["01"]
blocked-by: ["[[01-one-gate-not-three]]"]
open-blockers: 0
ready: true
tags: [ticket]
---
# Five switches that actually switch something

## Question

Kevin settled the shape: master plus five categories, two states each, master ANDs over all. **None
of it exists** - zero hits for `ProactiveCategory` or any proactive enum - and today `setMuted` has
**no callers at all**, so proactive cannot be turned off by any means.

Blocked by [the choke point](01-one-gate-not-three.md): until every raise passes one gate, a master
switch is a promise the code cannot keep.

Decide:

1. **Storage.** `ProactivePreferences` is SharedPreferences with one key. Five booleans plus a
   master is still trivially SharedPreferences - **or does this belong in Room** so it syncs to the
   other phone through `appDataFolder` like the rest of the driver's settings? **Two phones
   disagreeing about whether Alfred may speak is a real failure**, and `sync/` has never executed,
   so "it will sync" is an assumption to test rather than assert.
2. **Deprecating `muted`.** Does the new model replace it, wrap it, or keep it as the master's
   storage? It already ships and is read by `ProactiveGate` and `AmbientListener`. **A migration
   that silently flips Kevin's effective setting is unacceptable** - state what an existing
   `muted=false` becomes.
3. **Default state.** Today proactive is ON by default (inverted mute). **Should a fresh install be
   loud or quiet?** Quiet-by-default is the trustworthy answer and it means Kevin's own phone changes
   behaviour on upgrade unless the migration says otherwise. Decide both, together.
4. **The surface.** `SettingsScreen`/`SettingsRows` exist and `mission-control` owns aesthetics
   (settled decision 6). One row per category plus a master, or a sub-screen? **And what does a
   category row say when its own content does not exist yet** - Wellbeing has no nudges, Safety
   depends on an unstarted location map. A switch that governs nothing must not imply it does.
5. **Where the 11 existing raises land.** Each maps onto a category or is retired -
   [the choke point](01-one-gate-not-three.md) Q4 owns that call; this ticket implements it.

**This ticket is the buildable plumbing subset.** Once it and 01 land, the switches are real even
though most categories are still empty - which is the honest order: **make the kill switch true
before giving it more to kill.**
