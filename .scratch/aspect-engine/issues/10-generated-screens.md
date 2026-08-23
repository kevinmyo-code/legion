---
map: aspect-engine
ticket: "10"
title: "Generated screens: list, detail, form"
type: grilling
status: resolved
status-detail: "Resolved 2026-08-23: generated trio confirmed, plugin override with generated fallback."
blockers: ["03"]
blocked-by: ["[[03-engine-schema]]"]
open-blockers: 0
ready: false
tags: [ticket]
---
# Generated screens: list, detail, form

## Question

Charter decision 10: widgets are windows; behind them, three conventional screens per record
type, generated from field definitions - the guaranteed hands path (ADR 0035). Decide:

1. **List screen.** Columns/rows shown for an arbitrary record type (first N fields? a
   user-designated title field?), sort, filter, search, pagination for 400-row ledgers.
2. **Form generation.** Per field type: text/number/money/date/choice/reference (a picker over
   target records) /photo. Required-field enforcement, validation errors in words, and how a
   quarantining write is shown (the gate speaks here too).
3. **Detail screen.** Fields + computed fields + child records via references + provenance tag
   (DETERMINISTIC / LLM_RECONCILED / UNRECONCILED **in words** - sec 4 rule 7 applies to
   generated surfaces exactly as to hand-built ones).
4. **Escape hatch.** Can an aspect override a generated screen with a native one (plugin-provided
   detail for a car with live OBD)? Recommend yes via the plugin API, and the generated one
   remains the fallback so the hands path never vanishes.
5. **Theming.** Mission-control skin tokens applied to generated components - one visual
   language, no second design system.

## Answer

Resolved 2026-08-23 (Kevin, batched grilling).

1. Every record type gets generated **list, detail, and form** screens from its field
   definitions; they are the ADR 0035 hands path and can never vanish.
2. **Escape hatch: yes.** A plugin may override the detail screen (car with live OBD); the
   generated screen remains reachable as the fallback.
3. Detail screens show provenance **in words** (sec 4 rule 7 applies to generated surfaces).
   Forms enforce required fields and speak validation and quarantine in words.
4. Mission-control tokens skin all generated components; no second design system.
5. Column choice, title-field designation, search, and pagination are build work:
   [Build the widget pager](18-build-widget-pager.md) (screens ship with the pager).
