---
type: decision
status: built
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

## RULED 2026-08-28: the engine SURVIVES, scoped to user-created aspects. Nothing is deleted.

Delegated to me ("resolve everything with ur recommendations"); open to reversal, and this one
amends ruling 7, so it deserves the most argument.

**Ruling: `engine/` is not deleted. Its role narrows from "how every aspect stores data" to "how a
user-created aspect stores data", and that narrowing is now true rather than aspirational.**

### Why not delete it

Ruling 7 says the generic engine retires and ruling 6 accepted the cost - "field defs become
migrations, a new aspect needs a deploy rather than a metadata row, and the metadata layer stops
being the product". **Both of those are about where the SIX BUILT-IN ASPECTS store their data, and
that is now done.** Places, pantry, fleet, notes, dates and ledger are all off it. The correctness
win ruling 7 was after - one shape end to end, enforcement where a client cannot bypass it - has
landed in full.

What remains is different in kind. `EngineToolbox`'s `create_aspect`, the generated list/detail/form
screens and the widget pager are not plumbing for built-in aspects; they are **a shipped feature**
that lets Kevin add an aspect the code has never heard of. It has a voice path and a hands path
(ADR 0035), he fixed its "+" flow himself on 2026-08-25, and the widget pager is the dashboard.

Deleting them would remove a working capability with nothing in its place. Ruling 6 accepted that a
NEW aspect needs a deploy; it did not say the existing ability to make one should be torn out. And
ADR 0035 makes the removal worse than neutral: taking out both the voice and hands halves at once is
not "keeping them in step", it is deleting the capability.

**A ruling is a direction, not a mandate to remove something that works.** The engine costs 5,599
lines and no longer holds any built-in aspect's data. Deleting it buys tidiness and costs a feature.

### What this changes, concretely

- **Phase 6's "delete the engine" does not happen as written.** That is the amendment. Phase 6's
  other deletions are unaffected: `list_items`, the mirror, `SyncEngine` entries as they empty, and
  the statement parsers once C4 is proven all still go.
- The engine's remaining consumers are exactly: the five reconciles (configured-transition tools),
  `EngineToolbox` and the generated UI, `MidnightApplication`'s copier gates, and
  `ReingestDryRun`. Every one of those is either a migration tool or the user-created-aspect
  feature. **No built-in aspect reads or writes it.**
- The retirement copiers stay. They are one-time and idempotent, and deleting them would strand any
  install that has not yet run them.

### What would reverse this

If Kevin decides he does not want runtime-created aspects - that adding one via a deploy is fine -
then the generated UI and `EngineToolbox` go, and `engine/` goes with them in one clean removal. That
is a product call about a feature he uses, not a cleanup task, and it is his to make.

**Until then the honest state is: the engine is not the aspect layer any more, and it is still the
create-an-aspect layer.** That boundary is now enforced by a test rather than by intention - see
`EngineBoundaryTest`.

### CORRECTION 2026-08-28: my own claim above was overstated, and the test caught it

This ruling said "No built-in aspect reads or writes it." **That was not true when written.** Building
the boundary test found two live, non-comment engine touches the ruling's consumer list omitted, and
they were allow-listed with reasons rather than hidden by weakening the test:

1. **`vehicle/FleetEngineStore.kt` still co-writes the engine `Vehicle` record** alongside the legacy
   mirror, in one transaction, on every identity edit. It is not stale and not a regression - it is
   deliberate, and ticket 16 examined and kept it the same day. The reason is structural: fleet has no
   configured write path (ticket 14 made it a projection), so `FleetReconcile` is the only route
   vehicles reach Postgres, and it reads the engine as its upload source. The co-write is what keeps
   that source current.
2. **`engine/dates/DatesAgenda.kt` reads the engine** for the cross-aspect due-date merge, explicitly
   EXCLUDING its own and Notes' data. This one is not an exception to the ruling at all - it is the
   ruling working. A Dates surface reading a USER-CREATED aspect's due dates is exactly the surviving
   job this ticket preserves the engine for.

**Deliberately not "fixed".** Repointing `FleetReconcile`'s vehicle upload at the legacy table and
dropping the co-write would make the sentence above literally true and shrink the allow-list by one.
It would also churn fleet's identity path for tidiness - and this ruling's whole argument is that
tidiness does not justify disturbing something that works. Applying that standard to my own claim
rather than only to Kevin's feature is the point.

So the accurate sentence is: **no built-in aspect stores its DATA in the engine any more.** Fleet
keeps one co-write to feed its projection, and Dates reads other aspects through it by design.

