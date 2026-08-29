---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The engine's survivor clause was the phone's UI, and the phone is losing its UI

**Opened 2026-08-28 alongside [[0040-pc-is-the-primary-surface-phone-is-voice-first]], because that
ADR weakens the exact premise ticket 18 spared `engine/` on.**

## What ticket 18 decided, and why

Ticket 15 sequenced the engine's retirement and its step 6 was "delete `engine/`". Ticket 18
cancelled that step, and the reasoning was specific rather than sentimental:
`EngineToolbox.create_aspect`, the generated list/detail/form screens and the widget pager are **a
shipped, still-wanted feature** whose storage layer IS the engine. So `engine/` narrowed in scope to
"how a user-created aspect stores data" and `engine/EngineBoundaryTest` was written to enforce that
boundary.

That was correct on 2026-08-28 morning. By evening the phone's role had changed.

## What changed

ADR 0040: the PC becomes the surface for read/write/edit/monitor/ingest, and the phone becomes
voice-first with minimal UI - *"i ask it via voice, it generates a pop up modal."* The phone keeps
OBD, the voice companion, calendar, todos, lists, groceries and notes.

**A generated list/detail/form screen and a widget pager are browsing surfaces.** They are the
category of thing the phone is explicitly shedding. If nobody browses on the phone, the feature
`engine/` exists to serve may have no user left.

## The question

**Does `create_aspect` and its generated UI survive the phone's scope reduction?**

Three shapes, and this is Kevin's call:

1. **Delete it.** `engine/` goes, its entities and tables go, and a user-created aspect becomes
   something the PC surface does against Postgres directly - which is where schema work now lives
   anyway. Biggest deletion in the repo's history and the map has been circling it for a week.
2. **Keep it, voice-only.** `create_aspect` stays as a voice tool, and whatever it creates is read
   back through the modal rather than a generated screen. The generated UI dies, the storage layer
   lives. Note this collides with [[0035-every-voice-capability-has-a-hands-path]] unless the PC
   counts as the hands path.
3. **Move it.** The concept survives on the PC and the phone loses it entirely.

## What must be checked before ruling, not assumed

- **Has `create_aspect` ever been used?** If Kevin has created zero runtime aspects, option 1 costs
  nothing and the feature was speculative. If he has created some, they hold real data and deletion
  is a migration, not a deletion. **Query the device before deciding** - `aspects`/`record_types`
  rows that are not the six built-ins.
- **What still reads the engine?** Ticket 15 repointed every built-in aspect off it, and
  `EngineBoundaryTest` pins that. The remaining consumers should be only the generic UI and the
  meta-tools; confirm rather than trust the boundary test's scope.
- **The widget pager is `MainActivity`'s start destination.** Deleting the engine without deciding
  what HOME becomes leaves the app with no home screen. That is a UI decision hiding inside a
  storage decision, and it should be made deliberately.

## Not blocked, but not urgent either

Nothing depends on this. It is the largest remaining simplification available and it should be taken
when the PC surface exists and the phone's real shape is visible - deleting a home screen before its
replacement is designed is how an app becomes unusable between two correct states.
