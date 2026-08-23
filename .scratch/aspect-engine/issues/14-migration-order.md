---
map: aspect-engine
ticket: "14"
title: "Migration order for the existing aspects"
type: grilling
status: open
status-detail: ""
blockers: ["03", "11"]
blocked-by: ["[[03-engine-schema]]", "[[11-capability-plugin-api]]"]
open-blockers: 2
ready: false
tags: [ticket]
---
# Migration order for the existing aspects

## Question

Charter decision 3: all 48 entities migrate into the central store. This is the highest-stakes
Room work the repo has done, against real data on Kevin's A25. Decide the order and the per-aspect
plan:

1. **Order.** Recommend proving the engine on NEW ground first (a user-authored aspect + the
   Dates aspect), then the simplest existing aspect, then ledger (gate re-plumb), then fleet
   (biggest plugin surface). Notes/lists/places/pantry slot where? Justify against risk, not
   convenience.
2. **Per-aspect carve.** For each aspect: which entities become record types (with field defs),
   which stay plugin-internal state (OBD session config? key vault stays put), which die. The
   inventory from ticket 11 feeds this.
3. **Mechanics.** In-place Room migrations copying typed rows into the generic tables (verbatim
   SQL, additive, old tables dropped only after verification on-device), per-aspect migration
   tests, and the hash-verified-install discipline for every on-phone check.
4. **What freezes meanwhile.** While an aspect migrates, its old screens/tools keep working until
   the engine version is verified - cutover per aspect, never a big bang.
5. **Rollback.** If a migrated aspect is wrong on the phone, what is the path back? (Old tables
   retained N versions? Drive export before migration as the belt-and-braces copy?)

Resolution opens the per-aspect build tickets in the same commit.
