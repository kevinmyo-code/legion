---
type: decision
status: open
blocked_by: []
map: backend-erp
---

# The generic UI still runs on the engine, and no ticket covers it

**Found 2026-08-27 after finishing ticket 15's five aspect steps, by counting what still consumes
the engine. Ruling 7 names this surface but nothing schedules it.**

Steps 1-5 moved every ASPECT's unconfigured data path off the engine: places, pantry, fleet's
vehicles, notes, ledger. Grepping afterwards, the five repointed controllers reference
`RecordStore`/`engineRecordDao()` only in doc comments describing that they no longer use them.

**But the engine still backs a whole UI surface, and that is not an aspect - it is product.**

| Still on the engine | What it is |
|---|---|
| `ui/generated/GeneratedListScreen.kt`, `GeneratedDetailScreen.kt`, `GeneratedFormScreen.kt` | The screens driven by `field_defs` - the generic list/detail/form the engine exists to render |
| `ui/widgets/WidgetPagerScreen.kt` | The dashboard pager, reading `widget_instances` |
| `service/EngineToolbox.kt` | The nine engine meta-tools, the clerk, the schema generator, and the voice `create_aspect` handshake |

Ruling 7 says these retire with the engine: "`records`/`record_types`/`field_defs`, the generated
forms, the generated validation and the computed-field machinery all retire with it." So their
deletion is authorised in principle. **What is missing is what replaces them, and whether anything
should.**

## Why this is a product question, not a cleanup

These are not internal plumbing. The "+ add aspect" flow Kevin fixed on 2026-08-25 goes through
`EngineToolbox.manualCreateDraft` + `commitCreateAspect` - the same write path the voice
`create_aspect` confirm handshake uses, deliberately one implementation rather than two. Deleting
the engine deletes the ability to create an aspect at runtime at all.

Ruling 6 already accepted that cost in the abstract: "field defs become migrations, a new aspect
needs a deploy rather than a metadata row, **and the metadata layer stops being the product**." But
accepting it in a ruling and removing a working feature from the phone are different acts, and the
second one has not been scheduled or scoped.

## What needs deciding

1. **Do the generated screens and the widget pager get typed replacements, or do they go?** Ruling 6
   says a new aspect needs a deploy, which implies they go - but the widget pager is the dashboard,
   and `WidgetInstance` is a real user-arranged layout, not scaffolding.
2. **What happens to `create_aspect` and its voice handshake?** It is a shipped capability with a
   hands path (ADR 0035), and deleting it silently would violate the "every voice capability has a
   non-voice path" rule by removing both halves rather than keeping them in step.
3. **Ordering:** these are the last real engine consumers besides the reconciles (which are
   configured-transition tools and go when phase 6 does) and the two aspects already ticketed -
   fleet's `ServiceHistory`/`MaintenanceSchedule` (ticket 16) and Dates (ticket 17).

**Nothing about `engine/` can be deleted until 16, 17 and this are all answered.** That is three
open decisions standing between the current state and phase 6, and none of them is a coding task.
