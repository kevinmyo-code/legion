---
type: build
status: open
blocked_by: []
map: one-today
---

# A generated view for the questions no screen was built for

Kevin, 2026-09-01: *"i still want gen UI for some niche questions. can we integrate it? look for gen
ui sdks etc. i think gemini has em."*

## What exists in the world, checked rather than assumed

| Option | Status 2026-09-01 | Verdict |
|---|---|---|
| **A2UI** (Google's open agent-to-UI protocol) | v0.9.1 stable, v1.0 RC. Renderers ship for Lit, Angular and Flutter (GenUI SDK) | **No Jetpack Compose renderer.** It is on the published roadmap, not in the box. Adopting it today means writing the Compose renderer ourselves |
| **RemoteCompose** (AndroidX `androidx.compose.remote`) | `1.0.0-alpha18`, 2026-08-26. Eighteen alphas since 2025-12 | Solves UI authored in one process and rendered in another - Google uses it for Gemini-generated home-screen widgets. **LEGION has no cross-process surface.** Wrong problem, and alpha churn on top |
| **Gemini `responseSchema`** (structured output) | Shipped, already reachable on the key LEGION already uses | The one usable piece today |

## The finding that decides it: LEGION already implements the pattern

A2UI's whole thesis is *the agent names components from a trusted catalog and the client renders them
with its own native widgets, because shipping code across a trust boundary is unsafe.* This codebase
built that before the spec existed:

- `GlanceShape` - `NUMBER`, `LIST`, `STATUS_GRID`, `HEADLINE_LIST` (`service/GlanceCardController.kt`)
- `WidgetKind` - eight types (`engine/WidgetKind.kt`)
- `VoiceModalTarget` - `AGENDA`, `WHOLE_LIST`, `GROCERIES` (`service/VoiceModalController.kt`), whose
  own doc comment states the rule outright: *"voice is a LAUNCHER, not a renderer"*, and ADR 0040:
  *"not voice generated, voice called."*

**So: do not adopt an SDK. Extend the catalog.** Mirror A2UI's JSON field names while doing it, so
that when the Compose renderer lands, the renderer is what gets swapped and the protocol is not.

## Why this does not contradict ADR 0040

ADR 0040 ruled that voice CALLS pre-made modals rather than generating them, and that ruling stands
for every surface Kevin uses daily. This ticket is the narrow complement: a question the app has no
screen for, answered once. The distinction that keeps both true:

- A **modal** is a durable surface, hand-built, reachable by voice and by hand (ADR 0035). Unchanged.
- A **generated view** is ephemeral, exists only in response to one question, and is composed from
  the same hand-built primitives - never from model-authored layout.

The home screen, the day, the meters - all pre-made. This is the long tail only.

## The rule that makes it safe, and it is the whole ticket

**The model chooses the SHAPE and names the QUERY. It never emits a VALUE.**

The app runs the query against Room/Supabase and fills the numbers in. A view that rendered
model-produced figures would be the 2026-08-21 invented-lunch-appointment failure with a chart drawn
around it - and CLAUDE.md §4's gate would never fire, because no ingestion happened. There is no
anchor to reconcile a spoken number against; the only defence is that the number never comes from the
model in the first place.

Three consequences, all binding:

1. **The tool's parameters carry a query specification, not data.** An aggregation, a source, a
   window, a grouping - drawn from a closed vocabulary the app validates. An unrecognised value is a
   refusal, not a best-effort render.
2. **Provenance is rendered, in words** (§4 rule 5 and the §7 disclosure posture). The view states
   what it counted and what it excluded. `UNRECONCILED` rows are excluded from any total and said so
   in words, never by colour alone - see [[legion-trust-disclosures-are-not-furniture]].
3. **A view that cannot be built says so.** If the query vocabulary cannot express the question, the
   tool returns a failure result naming what it could not do, and the assistant says that. §7's
   outcome-verb rule needs a real result to stand on.

## What to build

- `service/GeneratedViewController.kt` - a sibling to `GlanceCardController` and
  `VoiceModalController`, same posture: pure ephemeral state, no coroutine scope, no Room table, not
  persisted. Read those two files first; the comment in `VoiceModalController` explaining why it is a
  sibling rather than an extension of `GlanceCardController` applies here too, and for the same
  reason - a different dismiss policy.
- A closed shape vocabulary. Start small and honest: `BAR_SERIES`, `LINE_SERIES`, `TOTAL_WITH_ROWS`.
  Three shapes that cover most one-off questions beat eight that are half-rendered.
- A validated query spec: aggregation, source, window, grouping, all from closed enums.
- `ui/GeneratedViewHost.kt` - mounted in `LegionShell`'s outer `Box` beside `GlanceCardOverlay` and
  `VoiceModalHost`, rendering with existing `ui/common/DeckPanels.kt` primitives only.
- A `show_generated_view` tool in `service/LiveToolbox.kt`, following the established pattern:
  declared via `fn(name, description, params, required)`, dispatched in the `when` returning `null`
  because the session controller owns the screen (exactly as `show_agenda_modal` does).
- **A hands path (ADR 0035).** Every voice capability has one. The same query builder must be
  reachable by hand from the Meters screen, calling the same controller. A capability reachable only
  by voice is not finished.
- Tests: the query spec validator rejects out-of-vocabulary values; a shape with no data renders the
  empty case rather than a zero; excluded unreconciled rows are named in the rendered output.

## Deliberately NOT in this ticket

**Pinning a generated view to the Meters screen.** It is the obvious next ask and it is a bigger
feature than this one: pinning must store the QUERY and re-run it, never the numbers, or the pinned
meter quietly becomes a screenshot of one Tuesday. That is durable state, which means a table, a
migration, and a sync channel. Its own ticket, after Kevin has judged whether the ephemeral version
earns its keep.
