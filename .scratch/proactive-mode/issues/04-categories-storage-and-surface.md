---
map: proactive-mode
ticket: 04
title: Five switches that actually switch something
type: grilling
status: resolved
status-detail: "2026-08-21, Kevin - 4 calls; Room-backed, quiet by default, existing setting carried"
blockers: ["01"]
blocked-by: ["[[01-one-gate-not-three]]"]
open-blockers: 0
ready: false
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

## Resolution - 2026-08-21 (Kevin, 4 calls)

### 1. Storage: Room, so it syncs

Two phones disagreeing about whether the assistant may speak is a real failure, and this is a
setting about *Kevin*, not about a handset. Costs a migration and a `SyncCodec` entry.

**Stated honestly, because the ticket asked for it rather than an assertion:** `sync/` has never
actually executed. Putting these in Room does not make them sync - it makes them *eligible* to, and
turns a guaranteed divergence into a bug that can be found and fixed. Do not write a doc comment
claiming the switches sync until a real device pair has proved it.

### 2 and 3. Default quiet; Kevin's own phone carries its current behaviour

These two are one decision and were taken together.

| | Fresh install | Kevin's existing phone (`muted=false`) |
|---|---|---|
| Master | **On** (it is a kill switch, and a kill switch defaults to not-killing) | On - unchanged |
| Safety | Off | **On** |
| Fleet | Off | **On** |
| Timing | Off | **On** |
| Wellbeing | Off | Off (no content exists) |
| Digest | Off | Off (no content exists) |

**A stranger's fresh install says nothing until they ask for it.** An assistant that speaks first
before being invited is the thing people uninstall, and a quiet default is what makes the switch
meaningful on day one rather than a thing you discover after being surprised.

**Kevin's effective behaviour does not change on upgrade.** The eleven existing raises map onto
Safety, Fleet and Timing, so those three come on for anyone who had `muted=false`. This is the
ticket's own "a migration that silently flips Kevin's setting is unacceptable", honoured in the
direction that changes nothing for him.

`muted` is **superseded, not kept**: the master is the new storage and the migration reads the old
key once to seed it. `ProactiveGate`'s reads move to the new model in the same change.

### 4. The surface: one row per category, and an empty category says so in words

`Wellbeing - nothing uses this yet.` Right there on the row.

Same posture as the digest builders' *"not logged, never 0"* and as the mute row's own status line:
**a switch that governs nothing must not imply it does.** Hiding the row would cost the map of what
is coming; greying it out would be a control with no explanation. Saying it is the only option that
is neither a surprise nor a lie.

`mission-control` still owns the aesthetics (settled decision 6) - this ticket owns what the rows
*say*, not how they look.

### 5. Where the existing raises land

| Category | Raises |
|---|---|
| Safety | coolant overheat, new trouble code, rough weather on the road |
| Fleet | NHTSA recalls, odometer milestone, two-hour break nudge |
| Timing | fired reminder, place arrival, incoming call, the startup opener |
| Wellbeing | *none yet* |
| Digest | *none yet* |

The retired ambient listener took the twelfth with it ([ticket 12](12-retire-ambient-listening.md)).