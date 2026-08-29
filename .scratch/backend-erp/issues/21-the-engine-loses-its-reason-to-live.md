---
type: decision
status: closed
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
voice-first with minimal UI. The phone keeps OBD, the voice companion, calendar, todos, lists,
groceries and notes.

**The replacement is PRE-MADE modals that voice foregrounds** (Kevin, clarifying the same day: *"not
voice generated, voice called. pre made modals, voice calls it to trigger it to foreground"*), and
that distinction is the whole of this ticket. A hand-written modal per capability is the OPPOSITE of
a screen composed at runtime from field definitions - it is deterministic, previewable, and diffable.

**So the engine's generated list/detail/form screens are not being replaced by something similar.
They are being replaced by their opposite.** They and the widget pager are browsing surfaces
composed at runtime, which is precisely the category the phone is shedding. If nobody browses on the
phone, the feature `engine/` exists to serve may have no user left.

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

## WITHDRAWN 2026-08-28, same day. The premise is gone.

Kevin: *"lets just keep the UI as is for now. classic home screen etc. just the voice called modals
that bring to foreground we add."*

**The phone is not losing its UI.** The widget pager, the generated screens, the Classic per-aspect
surfaces and the tab bar all stay. Voice-called modals are additive - a faster route into a shell
that keeps working exactly as it does now.

This ticket existed entirely because ADR 0040's first draft said the phone was shedding browsing
surfaces, which would have left `engine/` serving a feature with no user. That is no longer true, so
**ticket 18's survivor clause holds unchanged and `engine/` stays** for the reason it always had:
`create_aspect`, the generated list/detail/form screens and the widget pager are a shipped,
still-wanted feature whose storage layer it is.

**Nothing here needs deciding.** The two checks it listed are still worth doing if the question ever
returns - whether `create_aspect` has ever actually been used, and what HOME becomes - but there is
no question pending them today.

**Worth keeping from the false alarm:** replacing a working shell to chase a cleaner architecture is
how a month gets spent arriving back where it started. That UI was device-verified and corrected
against real use over weeks. Adding a shortcut to it costs one feature; rebuilding it costs the
weeks again.
